package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;
import io.netty.handler.codec.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLEngine;

import org.junit.Assert;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Tests a proxy chained to a downstream proxy. In addition to the usual
 * assertions, this also asserts that every request sent by the upstream proxy
 * was received by the downstream proxy.
 */
public class ChainedProxyTest extends BaseProxyTest {
    protected static final AtomicInteger DOWNSTREAM_PROXY_SERVER_PORT_SEQ = new AtomicInteger(
            59000);

    private int downstreamProxyPort;

    private static final AtomicLong REQUESTS_SENT_BY_UPSTREAM = new AtomicLong(
            0l);
    private static final AtomicLong REQUESTS_RECEIVED_BY_DOWNSTREAM = new AtomicLong(
            0l);
    private static final ConcurrentSkipListSet<TransportProtocol> TRANSPORTS_USED = new ConcurrentSkipListSet<TransportProtocol>();

    private static final ActivityTracker UPSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestSentToServer(FullFlowContext flowContext,
                io.netty.handler.codec.http.HttpRequest httpRequest) {
            REQUESTS_SENT_BY_UPSTREAM.incrementAndGet();
            TRANSPORTS_USED.add(flowContext.getChainedProxy()
                    .getTransportProtocol());
        }
    };
    private static final ActivityTracker DOWNSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestReceivedFromClient(FlowContext flowContext,
                HttpRequest httpRequest) {
            REQUESTS_RECEIVED_BY_DOWNSTREAM.incrementAndGet();
        };
    };

    private HttpProxyServer downstreamProxy;

    @Override
    protected void setUp() {
        // Set up ports from sequence
        downstreamProxyPort = DOWNSTREAM_PROXY_SERVER_PORT_SEQ
                .getAndIncrement();

        REQUESTS_SENT_BY_UPSTREAM.set(0);
        REQUESTS_RECEIVED_BY_DOWNSTREAM.set(0);
        TRANSPORTS_USED.clear();
        final SSLEngineSource sslEngineSource = new SelfSignedSSLEngineSource(
                "chain_proxy_keystore_1.jks");
        this.downstreamProxy = DefaultHttpProxyServer.bootstrap()
                .withName("Downstream")
                .withPort(downstreamProxyPort)
                .withTransportProtocol(UDT)
                .withSSLEngineSource(sslEngineSource)
                .plusActivityTracker(DOWNSTREAM_TRACKER).start();
        this.proxyServer = bootstrapProxy()
                .withName("Upstream")
                .withPort(proxyServerPort)
                .withChainProxyManager(new ChainedProxyManager() {
                    @Override
                    public void lookupChainedProxies(HttpRequest httpRequest,
                            Queue<ChainedProxy> chainedProxies) {
                        chainedProxies.add(new ChainedProxyAdapter() {
                            @Override
                            public InetSocketAddress getChainedProxyAddress() {
                                try {
                                    return new InetSocketAddress(InetAddress
                                            .getByName("127.0.0.1"),
                                            downstreamProxyPort);
                                } catch (UnknownHostException uhe) {
                                    throw new RuntimeException(
                                            "Unable to resolve 127.0.0.1?!");
                                }
                            }

                            @Override
                            public TransportProtocol getTransportProtocol() {
                                return TransportProtocol.UDT;
                            }

                            @Override
                            public boolean requiresEncryption() {
                                return true;
                            }

                            @Override
                            public SSLEngine newSSLEngine() {
                                return sslEngineSource.newSSLEngine();
                            }
                        });
                    }
                })
                .plusActivityTracker(UPSTREAM_TRACKER).start();
    }

    @Override
    protected void tearDown() throws Exception {
        this.downstreamProxy.stop();
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        assertThatDownstreamProxyReceivedSentRequests();
    }

    @Override
    public void testSimpleGetRequest() throws Exception {
        super.testSimpleGetRequest();
        assertThatDownstreamProxyReceivedSentRequests();
    }

    @Override
    public void testProxyWithBadAddress() throws Exception {
        super.testProxyWithBadAddress();
        assertThatDownstreamProxyReceivedSentRequests();
    }

    @Override
    protected boolean isChained() {
        return true;
    }

    private void assertThatDownstreamProxyReceivedSentRequests() {
        Assert.assertEquals(
                "Downstream proxy should have seen every request sent by upstream proxy",
                REQUESTS_SENT_BY_UPSTREAM.get(),
                REQUESTS_RECEIVED_BY_DOWNSTREAM.get());
        Assert.assertEquals(
                "1 and only 1 transport protocol should have been used to downstream proxy",
                1, TRANSPORTS_USED.size());
        Assert.assertTrue("UDT transport should have been used",
                TRANSPORTS_USED.contains(TransportProtocol.UDT));
    }
}
