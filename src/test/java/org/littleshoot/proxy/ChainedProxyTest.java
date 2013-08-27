package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;
import io.netty.handler.codec.http.HttpRequest;

import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.SSLContext;

import org.junit.Assert;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Tests a proxy chained to a downstream proxy. In addition to the usual
 * assertions, this also asserts that every request sent by the upstream proxy
 * was received by the downstream proxy.
 */
public class ChainedProxyTest extends BaseProxyTest {
    private static final int DOWNSTREAM_PROXY_PORT = PROXY_SERVER_PORT + 1;
    private static final String DOWNSTREAM_PROXY_HOST_AND_PORT = "127.0.0.1:"
            + DOWNSTREAM_PROXY_PORT;

    private static final AtomicLong REQUESTS_SENT_BY_UPSTREAM = new AtomicLong(
            0l);
    private static final AtomicLong REQUESTS_RECEIVED_BY_DOWNSTREAM = new AtomicLong(
            0l);
    private static final ConcurrentSkipListSet<TransportProtocol> TRANSPORTS_USED = new ConcurrentSkipListSet<TransportProtocol>();

    private static final ActivityTracker UPSTREAM_TRACKER = new ActivityTrackerAdapter() {
        public void requestSent(FlowContext flowContext,
                io.netty.handler.codec.http.HttpRequest httpRequest) {
            REQUESTS_SENT_BY_UPSTREAM.incrementAndGet();
            TRANSPORTS_USED.add(flowContext.getOutboundTransportProtocol());
        }
    };
    private static final ActivityTracker DOWNSTREAM_TRACKER = new ActivityTrackerAdapter() {
        public void requestReceivedFromClient(FlowContext flowContext,
                io.netty.handler.codec.http.HttpRequest httpRequest) {
            REQUESTS_RECEIVED_BY_DOWNSTREAM.incrementAndGet();
        };
    };

    private HttpProxyServer downstreamProxy;

    @Override
    protected void setUp() {
        REQUESTS_SENT_BY_UPSTREAM.set(0);
        REQUESTS_RECEIVED_BY_DOWNSTREAM.set(0);
        TRANSPORTS_USED.clear();
        final SSLContextSource sslContextSource = new SelfSignedSSLContextSource(
                "chain_proxy_keystore_1.jks");
        this.downstreamProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(DOWNSTREAM_PROXY_PORT)
                .withTransportProtocol(UDT)
                .withSslContextSource(sslContextSource).start();
        this.downstreamProxy.addActivityTracker(DOWNSTREAM_TRACKER);
        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_SERVER_PORT)
                .withChainProxyManager(new ChainedProxyManagerAdapter() {
                    public String getHostAndPort(HttpRequest httpRequest) {
                        return DOWNSTREAM_PROXY_HOST_AND_PORT;
                    }

                    @Override
                    public TransportProtocol getTransportProtocol() {
                        return TransportProtocol.UDT;
                    }

                    @Override
                    public boolean requiresEncryption(HttpRequest httpRequest) {
                        return true;
                    }

                    @Override
                    public SSLContext getSSLContext() {
                        return sslContextSource.getSSLContext();
                    }
                }).start();
        this.proxyServer.addActivityTracker(UPSTREAM_TRACKER);
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
