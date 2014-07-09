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
 * Base class for tests that test a proxy chained to an upstream proxy. In
 * addition to the usual assertions, this also asserts that every request sent
 * by the downstream proxy was received by the upstream proxy.
 */
public abstract class BaseChainedProxyTest extends BaseProxyTest {
    protected static final AtomicInteger UPSTREAM_PROXY_SERVER_PORT_SEQ = new AtomicInteger(
            59000);

    private int upstreamProxyPort;

    private final AtomicLong REQUESTS_SENT_BY_DOWNSTREAM = new AtomicLong(
            0l);
    private final AtomicLong REQUESTS_RECEIVED_BY_UPSTREAM = new AtomicLong(
            0l);
    private final ConcurrentSkipListSet<TransportProtocol> TRANSPORTS_USED = new ConcurrentSkipListSet<TransportProtocol>();

    private final ActivityTracker DOWNSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestSentToServer(FullFlowContext flowContext,
                io.netty.handler.codec.http.HttpRequest httpRequest) {
            REQUESTS_SENT_BY_DOWNSTREAM.incrementAndGet();
            TRANSPORTS_USED.add(flowContext.getChainedProxy()
                    .getTransportProtocol());
        }
    };

    private final ActivityTracker UPSTREAM_TRACKER = new ActivityTrackerAdapter() {
        @Override
        public void requestReceivedFromClient(FlowContext flowContext,
                HttpRequest httpRequest) {
            REQUESTS_RECEIVED_BY_UPSTREAM.incrementAndGet();
        };
    };

    private HttpProxyServer upstreamProxy;

    @Override
    protected void setUp() {
        // Set up ports from sequence
        upstreamProxyPort = UPSTREAM_PROXY_SERVER_PORT_SEQ
                .getAndIncrement();

        REQUESTS_SENT_BY_DOWNSTREAM.set(0);
        REQUESTS_RECEIVED_BY_UPSTREAM.set(0);
        TRANSPORTS_USED.clear();
        this.upstreamProxy = upstreamProxy().start();
        this.proxyServer = bootstrapProxy()
                .withName("Downstream")
                .withPort(proxyServerPort)
                .withChainProxyManager(chainedProxyManager())
                .plusActivityTracker(DOWNSTREAM_TRACKER).start();
    }

    protected HttpProxyServerBootstrap upstreamProxy() {
        return DefaultHttpProxyServer.bootstrap()
                .withName("Upstream")
                .withPort(upstreamProxyPort)
                .plusActivityTracker(UPSTREAM_TRACKER);
    }
    
    protected ChainedProxyManager chainedProxyManager() {
        return new ChainedProxyManager() {
            @Override
            public void lookupChainedProxies(HttpRequest httpRequest,
                    Queue<ChainedProxy> chainedProxies) {
                chainedProxies.add(newChainedProxy());
            }
        };
    }

    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy();
    }

    @Override
    protected void tearDown() throws Exception {
        this.upstreamProxy.stop();
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertThatUpstreamProxyReceivedSentRequests();
        }
    }

    @Override
    public void testSimpleGetRequest() throws Exception {
        super.testSimpleGetRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertThatUpstreamProxyReceivedSentRequests();
        }
    }

    @Override
    public void testProxyWithBadAddress() throws Exception {
        super.testProxyWithBadAddress();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertThatUpstreamProxyReceivedSentRequests();
        }
    }

    @Override
    protected boolean isChained() {
        return true;
    }

    private void assertThatUpstreamProxyReceivedSentRequests() {
        Assert.assertEquals(
                "Upstream proxy should have seen every request sent by downstream proxy",
                REQUESTS_SENT_BY_DOWNSTREAM.get(),
                REQUESTS_RECEIVED_BY_UPSTREAM.get());
        Assert.assertEquals(
                "1 and only 1 transport protocol should have been used to upstream proxy",
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
                        upstreamProxyPort);
            } catch (UnknownHostException uhe) {
                throw new RuntimeException(
                        "Unable to resolve 127.0.0.1?!");
            }
        }
    }
}
