/*
 * Copyright 2012 Matt Corallo.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.dogecoin.core;

import com.google.dogecoin.params.UnitTestParams;
import com.google.dogecoin.net.BlockingClientManager;
import com.google.dogecoin.net.NioClientManager;
import com.google.dogecoin.store.BlockStore;

import java.net.InetSocketAddress;

import static com.google.common.base.Preconditions.checkArgument;
import static org.junit.Assert.assertTrue;

/**
 * Utility class that makes it easy to work with mock NetworkConnections in PeerGroups.
 */
public class TestWithPeerGroup extends TestWithNetworkConnections {
    protected static final NetworkParameters params = UnitTestParams.get();
    protected PeerGroup peerGroup;

    protected VersionMessage remoteVersionMessage;
    private final ClientType clientType;

    public TestWithPeerGroup(ClientType clientType) {
        super(clientType);
        if (clientType != ClientType.NIO_CLIENT_MANAGER && clientType != ClientType.BLOCKING_CLIENT_MANAGER)
            throw new RuntimeException();
        this.clientType = clientType;
    }

    public void setUp(BlockStore blockStore) throws Exception {
        super.setUp(blockStore);

        remoteVersionMessage = new VersionMessage(unitTestParams, 1);
        remoteVersionMessage.localServices = VersionMessage.NODE_NETWORK;
        remoteVersionMessage.clientVersion = FilteredBlock.MIN_PROTOCOL_VERSION;
        initPeerGroup();
    }

    protected void initPeerGroup() {
        if (clientType == ClientType.NIO_CLIENT_MANAGER)
            peerGroup = new PeerGroup(unitTestParams, blockChain, new NioClientManager());
        else
            peerGroup = new PeerGroup(unitTestParams, blockChain, new BlockingClientManager());
        peerGroup.setPingIntervalMsec(0);  // Disable the pings as they just get in the way of most tests.
    }

    protected InboundMessageQueuer connectPeerWithoutVersionExchange(int id) throws Exception {
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 2000);
        Peer peer = peerGroup.connectTo(remoteAddress).getConnectionOpenFuture().get();
        // Claim we are connected to a different IP that what we really are, so tx confidence broadcastBy sets work
        peer.remoteIp = new InetSocketAddress("127.0.0.1", 2000 + id);
        InboundMessageQueuer writeTarget = newPeerWriteTargetQueue.take();
        writeTarget.peer = peer;
        return writeTarget;
    }
    
    protected InboundMessageQueuer connectPeer(int id) throws Exception {
        return connectPeer(id, remoteVersionMessage);
    }

    protected InboundMessageQueuer connectPeer(int id, VersionMessage versionMessage) throws Exception {
        checkArgument(versionMessage.hasBlockChain());
        InboundMessageQueuer writeTarget = connectPeerWithoutVersionExchange(id);
        // Complete handshake with the peer - send/receive version(ack)s, receive bloom filter
        writeTarget.sendMessage(versionMessage);
        writeTarget.sendMessage(new VersionAck());
        assertTrue(writeTarget.nextMessageBlocking() instanceof VersionMessage);
        assertTrue(writeTarget.nextMessageBlocking() instanceof VersionAck);
        if (versionMessage.isBloomFilteringSupported()) {
            assertTrue(writeTarget.nextMessageBlocking() instanceof BloomFilter);
            assertTrue(writeTarget.nextMessageBlocking() instanceof MemoryPoolMessage);
        }
        return writeTarget;
    }
}