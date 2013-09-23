package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Queue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Assert;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Base class for tests that test a proxy chained to a downstream proxy. In
 * addition to the usual assertions, this also asserts that every request sent
 * by the upstream proxy was received by the downstream proxy.
 */
public abstract class BaseChainedProxyTest extends BaseProxyTest {
    protected static final AtomicInteger DOWNSTREAM_PROXY_SERVER_PORT_SEQ = new AtomicInteger(
            59000);

    private int downstreamProxyPort;

    private final AtomicLong REQUESTS_SENT_BY_UPSTREAM = new AtomicLong(
            0l);
    private final AtomicLong REQUESTS_RECEIVED_BY_DOWNSTREAM = new AtomicLong(
            0l);
    private final ConcurrentSkipListSet<TransportProtocol> TRANSPORTS_USED = new ConcurrentSkipListSet<TransportProtocol>();

    private final ActivityTracker UPSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestSentToServer(FullFlowContext flowContext,
                io.netty.handler.codec.http.HttpRequest httpRequest) {
            REQUESTS_SENT_BY_UPSTREAM.incrementAndGet();
            TRANSPORTS_USED.add(flowContext.getChainedProxy()
                    .getTransportProtocol());
        }
    };

    private final ActivityTracker DOWNSTREAM_TRACKER = new ActivityTrackerAdapter() {
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
        this.downstreamProxy = downstreamProxy().start();
        this.proxyServer = bootstrapProxy()
                .withName("Upstream")
                .withPort(proxyServerPort)
                .withChainProxyManager(new ChainedProxyManager() {
                    @Override
                    public void lookupChainedProxies(HttpRequest httpRequest,
                            Queue<ChainedProxy> chainedProxies) {
                        chainedProxies.add(newChainedProxy());
                    }
                })
                .plusActivityTracker(UPSTREAM_TRACKER).start();
    }

    protected HttpProxyServerBootstrap downstreamProxy() {
        return DefaultHttpProxyServer.bootstrap()
                .withName("Downstream")
                .withPort(downstreamProxyPort)
                .plusActivityTracker(DOWNSTREAM_TRACKER);
    }

    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy();
    }

    @Override
    protected void tearDown() throws Exception {
        this.downstreamProxy.stop();
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        if (!expectBadGatewayForEverything()) {
            assertThatDownstreamProxyReceivedSentRequests();
        }
    }

    @Override
    public void testSimpleGetRequest() throws Exception {
        super.testSimpleGetRequest();
        if (!expectBadGatewayForEverything()) {
            assertThatDownstreamProxyReceivedSentRequests();
        }
    }

    @Override
    public void testProxyWithBadAddress() throws Exception {
        super.testProxyWithBadAddress();
        if (!expectBadGatewayForEverything()) {
            assertThatDownstreamProxyReceivedSentRequests();
        }
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
        Assert.assertTrue("Correct transport should have been used",
                TRANSPORTS_USED.contains(newChainedProxy()
                        .getTransportProtocol()));
    }

    protected class BaseChainedProxy extends ChainedProxyAdapter {
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
    }
}
