package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.HttpClientUtil;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class HttpFilterTest {
    private Server webServer;
    private HttpProxyServer proxyServer;
    private int webServerPort;

    private ClientAndServer mockServer;
    private int mockServerPort;

    @Before
    public void setUp() throws Exception {
        webServer = new Server(0);
        webServer.start();
        webServerPort = TestUtils.findLocalHttpPort(webServer);

        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();

    }

    @After
    public void tearDown() throws Exception {
        try {
            if (webServer != null) {
                webServer.stop();
            }
        } finally {
            try {
                if (proxyServer != null) {
                    proxyServer.abort();
                }
            } finally {
                if (mockServer != null) {
                    mockServer.stop();
                }
            }
        }
    }

    /**
     * Sets up the HttpProxyServer instance for a test. This method initializes the proxyServer and proxyPort method variables, and should
     * be called before making any requests through the proxy server.
     *
     * The proxy cannot be created in an @Before method because the filtersSource must be initialized by each test before the proxy is
     * created.
     *
     * @param filtersSource HTTP filters source
     */
    private void setUpHttpProxyServer(HttpFiltersSource filtersSource) {
        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();

        final InetSocketAddress isa = new InetSocketAddress("127.0.0.1", proxyServer.getListenAddress().getPort());
        while (true) {
            try (Socket sock = new Socket()) {
                sock.connect(isa);
                break;
            } catch (final IOException e) {
                // Keep trying.
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while verifying proxy is connectable");
            }
        }
    }

    @Test
    public void testFiltering() throws Exception {
        final AtomicInteger shouldFilterCalls = new AtomicInteger(0);
        final AtomicInteger filterResponseCalls = new AtomicInteger(0);
        final AtomicInteger fullHttpRequestsReceived = new AtomicInteger(0);
        final AtomicInteger fullHttpResponsesReceived = new AtomicInteger(0);
        final Queue<HttpRequest> associatedRequests =
                new LinkedList<>();

        final AtomicInteger requestCount = new AtomicInteger(0);
        final AtomicLongArray proxyToServerRequestSendingNanos = new AtomicLongArray(new long[] { -1, -1, -1, -1, -1 });
        final AtomicLongArray proxyToServerRequestSentNanos = new AtomicLongArray(new long[] { -1, -1, -1,-1, -1 });
        final AtomicLongArray serverToProxyResponseReceivingNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray serverToProxyResponseReceivedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionQueuedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionStartedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionSucceededNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionFailedNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionStartedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionSSLHandshakeStartedNanos = new AtomicLongArray(new long[] {-1, -1, -1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionFailedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionSucceededNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray serverToProxyResponseTimedOutNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });

        final AtomicReference<ChannelHandlerContext> serverCtxReference = new AtomicReference<>();

        final String url1 = "http://localhost:" + webServerPort + "/";
        final String url2 = "http://localhost:" + webServerPort + "/testing";
        final String url3 = "http://localhost:" + webServerPort + "/testing2";
        final String url4 = "http://localhost:" + webServerPort + "/testing3";
        final String url5 = "http://localhost:" + webServerPort + "/testing4";

        final HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                shouldFilterCalls.incrementAndGet();
                associatedRequests.add(originalRequest);

                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(
                            HttpObject httpObject) {
                        fullHttpRequestsReceived.incrementAndGet();
                        if (httpObject instanceof HttpRequest) {
                            HttpRequest httpRequest = (HttpRequest) httpObject;
                            if (httpRequest.uri().equals(url2)) {
                                return new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.FORBIDDEN);
                            }
                        }
                        return null;
                    }

                    @Override
                    public HttpResponse proxyToServerRequest(
                            HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpRequest httpRequest = (HttpRequest) httpObject;
                            if (httpRequest.uri().equals("/testing2")) {
                                return new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.FORBIDDEN);
                            }
                        }
                        return null;
                    }

                    @Override
                    public void proxyToServerRequestSending() {
                        proxyToServerRequestSendingNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerRequestSent() {
                        proxyToServerRequestSentNanos.set(requestCount.get(), now());
                    }

                    public HttpObject serverToProxyResponse(
                            HttpObject httpObject) {
                        if (originalRequest.uri().contains("testing3")) {
                            return new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.FORBIDDEN);
                        }
                        filterResponseCalls.incrementAndGet();
                        if (httpObject instanceof FullHttpResponse) {
                            fullHttpResponsesReceived.incrementAndGet();
                        }
                        if (httpObject instanceof HttpResponse) {
                            ((HttpResponse) httpObject).headers().set(
                                    "Header-Pre", "1");
                        }
                        return httpObject;
                    }

                    @Override
                    public void serverToProxyResponseTimedOut() {
                        serverToProxyResponseTimedOutNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void serverToProxyResponseReceiving() {
                        serverToProxyResponseReceivingNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void serverToProxyResponseReceived() {
                        serverToProxyResponseReceivedNanos.set(requestCount.get(), now());
                    }

                    public HttpObject proxyToClientResponse(
                            HttpObject httpObject) {
                        if (originalRequest.uri().contains("testing4")) {
                            return new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.FORBIDDEN);
                        }
                        if (httpObject instanceof HttpResponse) {
                            ((HttpResponse) httpObject).headers().set(
                                    "Header-Post", "2");
                        }
                        return httpObject;
                    }

                    @Override
                    public void proxyToServerConnectionQueued() {
                        proxyToServerConnectionQueuedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public InetSocketAddress proxyToServerResolutionStarted(
                            String resolvingServerHostAndPort) {
                        proxyToServerResolutionStartedNanos.set(requestCount.get(), now());
                        return super.proxyToServerResolutionStarted(resolvingServerHostAndPort);
                    }

                    @Override
                    public void proxyToServerResolutionFailed(String hostAndPort) {
                        proxyToServerResolutionFailedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(
                            String serverHostAndPort,
                            InetSocketAddress resolvedRemoteAddress) {
                        proxyToServerResolutionSucceededNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionStarted() {
                        proxyToServerConnectionStartedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionSSLHandshakeStarted() {
                        proxyToServerConnectionSSLHandshakeStartedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionFailed() {
                        proxyToServerConnectionFailedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionSucceeded(ChannelHandlerContext ctx) {
                        proxyToServerConnectionSucceededNanos.set(requestCount.get(), now());
                        serverCtxReference.set(ctx);
                    }

                };
            }

            public int getMaximumRequestBufferSizeInBytes() {
                return 1024 * 1024;
            }

            public int getMaximumResponseBufferSizeInBytes() {
                return 1024 * 1024;
            }
        };

        setUpHttpProxyServer(filtersSource);

        org.apache.http.HttpResponse response1 = HttpClientUtil.performHttpGet(url1, proxyServer);
        // sleep for a short amount of time, to allow the filter methods to be invoked
        Thread.sleep(500);
        assertEquals(
                "Response should have included the custom header from our pre filter",
                "1", response1.getFirstHeader("Header-Pre").getValue());
        assertEquals(
                "Response should have included the custom header from our post filter",
                "2", response1.getFirstHeader("Header-Post").getValue());

        assertEquals(1, associatedRequests.size());
        assertEquals(1, shouldFilterCalls.get());
        assertEquals(1, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        int i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i), lessThan(proxyToServerResolutionStartedNanos.get(i)));
        assertThat(proxyToServerResolutionStartedNanos.get(i), lessThan(proxyToServerResolutionSucceededNanos.get(i)));
        assertThat(proxyToServerResolutionSucceededNanos.get(i), lessThan(proxyToServerConnectionStartedNanos.get(i)));
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos.get(i));
        assertEquals(-1, proxyToServerConnectionFailedNanos.get(i));
        assertEquals(-1, proxyToServerResolutionFailedNanos.get(i));
        assertEquals(-1, serverToProxyResponseTimedOutNanos.get(i));
        assertThat(proxyToServerConnectionStartedNanos.get(i), lessThan(proxyToServerConnectionSucceededNanos.get(i)));
        assertThat(proxyToServerConnectionSucceededNanos.get(i), lessThan(proxyToServerRequestSendingNanos.get(i)));
        assertThat(proxyToServerRequestSendingNanos.get(i), lessThan(proxyToServerRequestSentNanos.get(i)));
        assertThat(proxyToServerRequestSentNanos.get(i), lessThan(serverToProxyResponseReceivingNanos.get(i)));
        assertThat(serverToProxyResponseReceivingNanos.get(i), lessThan(serverToProxyResponseReceivedNanos.get(i)));

        // We just open a second connection here since reusing the original
        // connection is inconsistent.
        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response2 = HttpClientUtil.performHttpGet(url2, proxyServer);
        Thread.sleep(500);

        assertEquals(403, response2.getStatusLine().getStatusCode());

        assertEquals(2, associatedRequests.size());
        assertEquals(2, shouldFilterCalls.get());
        assertEquals(2, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response3 = HttpClientUtil.performHttpGet(url3, proxyServer);
        Thread.sleep(500);

        assertEquals(403, response3.getStatusLine().getStatusCode());

        assertEquals(3, associatedRequests.size());
        assertEquals(3, shouldFilterCalls.get());
        assertEquals(3, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i), lessThan(proxyToServerResolutionStartedNanos.get(i)));
        assertThat(proxyToServerResolutionStartedNanos.get(i), lessThan(proxyToServerResolutionSucceededNanos.get(i)));
        assertEquals(-1, proxyToServerConnectionStartedNanos.get(i));
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos.get(i));
        assertEquals(-1, proxyToServerConnectionFailedNanos.get(i));
        assertEquals(-1, proxyToServerConnectionSucceededNanos.get(i));
        assertEquals(-1, proxyToServerRequestSendingNanos.get(i));
        assertEquals(-1, proxyToServerRequestSentNanos.get(i));
        assertEquals(-1, serverToProxyResponseReceivingNanos.get(i));
        assertEquals(-1, serverToProxyResponseReceivedNanos.get(i));
        assertEquals(-1, proxyToServerResolutionFailedNanos.get(i));
        assertEquals(-1, serverToProxyResponseTimedOutNanos.get(i));

        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        final HttpRequest third = associatedRequests.remove();

        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertEquals(url1, first.uri());
        assertEquals(url2, second.uri());
        assertEquals(url3, third.uri());

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response4 = HttpClientUtil.performHttpGet(url4, proxyServer);
        Thread.sleep(500);

        i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i), lessThan(proxyToServerResolutionStartedNanos.get(i)));
        assertThat(proxyToServerResolutionStartedNanos.get(i), lessThan(proxyToServerResolutionSucceededNanos.get(i)));
        assertThat(proxyToServerResolutionSucceededNanos.get(i), lessThan(proxyToServerConnectionStartedNanos.get(i)));
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos.get(i));
        assertEquals(-1, proxyToServerConnectionFailedNanos.get(i));
        assertEquals(-1, proxyToServerResolutionFailedNanos.get(i));
        assertEquals(-1, serverToProxyResponseTimedOutNanos.get(i));
        assertThat(proxyToServerConnectionStartedNanos.get(i), lessThan(proxyToServerConnectionSucceededNanos.get(i)));
        assertThat(proxyToServerConnectionSucceededNanos.get(i), lessThan(proxyToServerRequestSendingNanos.get(i)));
        assertThat(proxyToServerRequestSendingNanos.get(i), lessThan(proxyToServerRequestSentNanos.get(i)));
        assertThat(proxyToServerRequestSentNanos.get(i), lessThan(serverToProxyResponseReceivingNanos.get(i)));
        assertThat(serverToProxyResponseReceivingNanos.get(i), lessThan(serverToProxyResponseReceivedNanos.get(i)));

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response5 = HttpClientUtil.performHttpGet(url5, proxyServer);

        assertEquals(403, response4.getStatusLine().getStatusCode());
        assertEquals(403, response5.getStatusLine().getStatusCode());

        assertNotNull("Server channel context from proxyToServerConnectionSucceeded() should not be null", serverCtxReference.get());
        InetSocketAddress remoteAddress = (InetSocketAddress) serverCtxReference.get().channel().remoteAddress();
        assertNotNull("Server's remoteAddress from proxyToServerConnectionSucceeded() should not be null", remoteAddress);
        // make sure we're getting the right remote address (and therefore the right server channel context) in the
        // proxyToServerConnectionSucceeded() filter method
        assertEquals("Server's remoteAddress should connect to localhost", "localhost", remoteAddress.getHostName());
        assertEquals("Server's port should match the web server port", webServerPort, remoteAddress.getPort());

        webServer.stop();
    }

    @Test
    public void testResolutionStartedFilterReturnsUnresolvedAddress() throws Exception {
        final AtomicBoolean resolutionSucceeded = new AtomicBoolean(false);

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public InetSocketAddress proxyToServerResolutionStarted(String resolvingServerHostAndPort) {
                        return InetSocketAddress.createUnresolved("localhost", webServerPort);
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress) {
                        assertFalse("expected to receive a resolved InetSocketAddress", resolvedRemoteAddress.isUnresolved());
                        resolutionSucceeded.set(true);
                    }
                };
            }
        };

        setUpHttpProxyServer(filtersSource);

        HttpClientUtil.performHttpGet("http://localhost:" + webServerPort + "/", proxyServer);
        Thread.sleep(500);

        assertTrue("proxyToServerResolutionSucceeded method was not called", resolutionSucceeded.get());
    }

    @Test
    public void testResolutionFailedCalledAfterDnsFailure() throws Exception {
        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return filter;
            }
        };

        HostResolver mockFailingResolver = mock(HostResolver.class);
        when(mockFailingResolver.resolve("www.doesnotexist", 80)).thenThrow(new UnknownHostException("www.doesnotexist"));

        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withServerResolver(mockFailingResolver)
                .start();

        HttpClientUtil.performHttpGet("http://www.doesnotexist/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertFalse("proxyToServerResolutionSucceeded method was called but should not have been", filter.isProxyToServerResolutionSucceededInvoked());
        assertTrue("proxyToServerResolutionFailed method was not called", filter.isProxyToServerResolutionFailedInvoked());

        assertTrue("Expected filter method to be called", filter.isClientToProxyRequestInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionQueuedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerResolutionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToClientResponseInvoked());

        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionSucceededInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSendingInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSentInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionSSLHandshakeStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivingInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseTimedOutInvoked());
    }

    @Test
    public void testConnectionFailedCalledAfterConnectionFailure() throws Exception {
        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return filter;
            }
        };

        setUpHttpProxyServer(filtersSource);

        // port 0 is not connectable
        HttpClientUtil.performHttpGet("http://localhost:0/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertFalse("proxyToServerConnectionSucceeded should not be called when connection fails", filter.isProxyToServerConnectionSucceededInvoked());
        assertTrue("proxyToServerConnectionFailed should be called when connection fails", filter.isProxyToServerConnectionFailedInvoked());

        assertTrue("Expected filter method to be called", filter.isClientToProxyRequestInvoked());
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertTrue("Expected filter method to be called", filter.isProxyToServerRequestInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionQueuedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerResolutionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerResolutionSucceededInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToClientResponseInvoked());

        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSendingInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSentInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionSSLHandshakeStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivingInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseTimedOutInvoked());
    }

    /**
     * Verifies the proper filters are invoked when an attempt to connect to an unencrypted upstream chained proxy fails.
     */
    @Test
    public void testFiltersAfterUnencryptedConnectionToUpstreamProxyFails() throws Exception {
        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return filter;
            }
        };

        // set up the proxy that the HTTP client will connect to
        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withChainProxyManager((httpRequest, chainedProxies, clientDetails) -> chainedProxies.add(new ChainedProxyAdapter() {
                    @Override
                    public InetSocketAddress getChainedProxyAddress() {
                        // port 0 is unconnectable
                        return new InetSocketAddress("127.0.0.1", 0);
                    }
                }))
                .start();

        // the server doesn't have to exist, since the connection to the chained proxy will fail
        HttpClientUtil.performHttpGet("http://localhost:1234/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertFalse("proxyToServerConnectionSucceeded should not be called when connection to chained proxy fails", filter.isProxyToServerConnectionSucceededInvoked());
        assertTrue("proxyToServerConnectionFailed should be called when connection to chained proxy fails", filter.isProxyToServerConnectionFailedInvoked());

        assertTrue("Expected filter method to be called", filter.isClientToProxyRequestInvoked());
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertTrue("Expected filter method to be called", filter.isProxyToServerRequestInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionQueuedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToClientResponseInvoked());

        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionSSLHandshakeStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionSucceededInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSendingInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSentInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivingInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseTimedOutInvoked());
    }

    /**
     * Verifies the proper filters are invoked when an attempt to connect to an upstream chained proxy over SSL fails.
     * (The proxyToServerConnectionFailed() filter method is particularly important.)
     */
    @Test
    public void testFiltersAfterSSLConnectionToUpstreamProxyFails() throws Exception {
        // create an upstream chained proxy using the same SSL engine as the chained proxy tests
        final HttpProxyServer chainedProxy = DefaultHttpProxyServer.bootstrap()
                .withName("ChainedProxy")
                .withPort(0)
                .withSslEngineSource(new SelfSignedSslEngineSource("chain_proxy_keystore_1.jks"))
                .start();

        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return filter;
            }
        };

        // set up the proxy that the HTTP client will connect to
        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withChainProxyManager((httpRequest, chainedProxies, clientDetails) -> chainedProxies.add(new ChainedProxyAdapter() {
                    @Override
                    public InetSocketAddress getChainedProxyAddress() {
                        return chainedProxy.getListenAddress();
                    }

                    @Override
                    public boolean requiresEncryption() {
                        return true;
                    }

                    @Override
                    public SSLEngine newSslEngine() {
                        // use the same "bad" keystore as BadServerAuthenticationTCPChainedProxyTest
                        return new SelfSignedSslEngineSource("chain_proxy_keystore_2.jks").newSslEngine();
                    }
                }))
                .start();

        // the server doesn't have to exist, since the connection to the chained proxy will fail
        HttpClientUtil.performHttpGet("http://localhost:1234/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertFalse("proxyToServerConnectionSucceeded should not be called when connection to chained proxy fails", filter.isProxyToServerConnectionSucceededInvoked());
        assertTrue("proxyToServerConnectionFailed should be called when connection to chained proxy fails", filter.isProxyToServerConnectionFailedInvoked());

        assertTrue("Expected filter method to be called", filter.isClientToProxyRequestInvoked());
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertTrue("Expected filter method to be called", filter.isProxyToServerRequestInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionQueuedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToClientResponseInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionSSLHandshakeStartedInvoked());

        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionSucceededInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSendingInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSentInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivingInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseTimedOutInvoked());
    }

    @Test
    public void testResponseTimedOutInvokedAfterServerTimeout() throws Exception {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/servertimeout"),
                Times.once())
                .respond(response()
                        .withStatusCode(200)
                        .withDelay(TimeUnit.SECONDS, 10)
                        .withBody("success"));

        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return filter;
            }
        };

        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withIdleConnectionTimeout(3)
                .start();

        org.apache.http.HttpResponse httpResponse = HttpClientUtil.performHttpGet("http://localhost:" + mockServerPort + "/servertimeout", proxyServer);
        assertEquals("Expected to receive an HTTP 504 Gateway Timeout from proxy", 504, httpResponse.getStatusLine().getStatusCode());

        Thread.sleep(500);
        
        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertTrue("Expected filter method to be called", filter.isServerToProxyResponseTimedOutInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivingInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivedInvoked());

        assertTrue("Expected filter method to be called", filter.isClientToProxyRequestInvoked());
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertTrue("Expected filter method to be called", filter.isProxyToServerRequestInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionQueuedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerResolutionStartedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerResolutionSucceededInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerRequestSendingInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerRequestSentInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionSucceededInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToClientResponseInvoked());

        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionSSLHandshakeStartedInvoked());
    }

    @Test
    public void testRequestSentInvokedAfterLastHttpContentSent() throws Exception {
        final AtomicBoolean lastHttpContentProcessed = new AtomicBoolean(false);
        final AtomicBoolean requestSentCallbackInvoked = new AtomicBoolean(false);

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse proxyToServerRequest(HttpObject httpObject) {
                        if (httpObject instanceof LastHttpContent) {
                            assertFalse("requestSentCallback should not be invoked until the LastHttpContent is processed", requestSentCallbackInvoked.get());

                            lastHttpContentProcessed.set(true);
                        }

                        return null;
                    }

                    @Override
                    public void proxyToServerRequestSent() {
                        // proxyToServerRequestSent should only be invoked after the entire request, including payload, has been sent to the server
                        assertTrue("proxyToServerRequestSent callback invoked before LastHttpContent was received from the client and sent to the server", lastHttpContentProcessed.get());

                        requestSentCallbackInvoked.set(true);
                    }
                };
            }
        };

        setUpHttpProxyServer(filtersSource);

        // test with a POST request with a payload. post a large amount of data, to force chunked content.
        HttpClientUtil.performHttpPost("http://localhost:" + webServerPort + "/", 50000, proxyServer);
        Thread.sleep(500);

        assertTrue("proxyToServerRequest callback was not invoked for LastHttpContent for chunked POST", lastHttpContentProcessed.get());
        assertTrue("proxyToServerRequestSent callback was not invoked for chunked POST", requestSentCallbackInvoked.get());

        // test with a non-payload-bearing GET request.
        lastHttpContentProcessed.set(false);
        requestSentCallbackInvoked.set(false);

        HttpClientUtil.performHttpGet("http://localhost:" + webServerPort + "/", proxyServer);
        Thread.sleep(500);

        assertTrue("proxyToServerRequest callback was not invoked for LastHttpContent for GET", lastHttpContentProcessed.get());
        assertTrue("proxyToServerRequestSent callback was not invoked for GET", requestSentCallbackInvoked.get());
    }

    /**
     * Verifies that the proxy properly handles a null HttpFilters instance, as allowed in the
     * {@link HttpFiltersSource#filterRequest(HttpRequest, ChannelHandlerContext)} documentation.
     */
    @Test
    public void testNullHttpFilterSource() throws Exception {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/testNullHttpFilterSource"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return null;
            }
        };

        setUpHttpProxyServer(filtersSource);

        org.apache.http.HttpResponse httpResponse = HttpClientUtil.performHttpGet("http://localhost:" + mockServerPort + "/testNullHttpFilterSource", proxyServer);
        Thread.sleep(500);

        assertEquals("Expected to receive an HTTP 200 from proxy", 200, httpResponse.getStatusLine().getStatusCode());
    }

    private long now() {
        // using nanoseconds instead of milliseconds, since it is extremely unlikely that any two callbacks would be invoked in the same nanosecond,
        // even on very fast hardware
        return System.nanoTime();
    }

    /**
     * HttpFilters instance that monitors HttpFilters methods and tracks which methods have been invoked.
     */
    private static class HttpFiltersMethodInvokedAdapter implements HttpFilters {
        private final AtomicBoolean proxyToServerConnectionFailed = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionSucceeded = new AtomicBoolean(false);
        private final AtomicBoolean clientToProxyRequest = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerRequest = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerRequestSending = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerRequestSent = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponse = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponseReceiving = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponseReceived = new AtomicBoolean(false);
        private final AtomicBoolean proxyToClientResponse = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionStarted = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionQueued = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerResolutionStarted = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerResolutionFailed = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerResolutionSucceeded = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionSSLHandshakeStarted = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponseTimedOut = new AtomicBoolean(false);

        public boolean isProxyToServerConnectionFailedInvoked() {
            return proxyToServerConnectionFailed.get();
        }

        public boolean isProxyToServerConnectionSucceededInvoked() {
            return proxyToServerConnectionSucceeded.get();
        }

        public boolean isClientToProxyRequestInvoked() {
            return clientToProxyRequest.get();
        }

        public boolean isProxyToServerRequestInvoked() {
            return proxyToServerRequest.get();
        }

        public boolean isProxyToServerRequestSendingInvoked() {
            return proxyToServerRequestSending.get();
        }

        public boolean isProxyToServerRequestSentInvoked() {
            return proxyToServerRequestSent.get();
        }

        public boolean isServerToProxyResponseInvoked() {
            return serverToProxyResponse.get();
        }

        public boolean isServerToProxyResponseReceivingInvoked() {
            return serverToProxyResponseReceiving.get();
        }

        public boolean isServerToProxyResponseReceivedInvoked() {
            return serverToProxyResponseReceived.get();
        }

        public boolean isProxyToClientResponseInvoked() {
            return proxyToClientResponse.get();
        }

        public boolean isProxyToServerConnectionStartedInvoked() {
            return proxyToServerConnectionStarted.get();
        }

        public boolean isProxyToServerConnectionQueuedInvoked() {
            return proxyToServerConnectionQueued.get();
        }

        public boolean isProxyToServerResolutionStartedInvoked() {
            return proxyToServerResolutionStarted.get();
        }

        public boolean isProxyToServerResolutionFailedInvoked() {
            return proxyToServerResolutionFailed.get();
        }

        public boolean isProxyToServerResolutionSucceededInvoked() {
            return proxyToServerResolutionSucceeded.get();
        }

        public boolean isProxyToServerConnectionSSLHandshakeStartedInvoked() {
            return proxyToServerConnectionSSLHandshakeStarted.get();
        }

        public boolean isServerToProxyResponseTimedOutInvoked() {
            return serverToProxyResponseTimedOut.get();
        }

        @Override
        public void proxyToServerConnectionFailed() {
            proxyToServerConnectionFailed.set(true);
        }

        @Override
        public void proxyToServerConnectionSucceeded(ChannelHandlerContext serverCtx) {
            proxyToServerConnectionSucceeded.set(true);
        }

        @Override
        public HttpResponse clientToProxyRequest(HttpObject httpObject) {
            clientToProxyRequest.set(true);
            return null;
        }

        @Override
        public HttpResponse proxyToServerRequest(HttpObject httpObject) {
            proxyToServerRequest.set(true);
            return null;
        }

        @Override
        public void proxyToServerRequestSending() {
            proxyToServerRequestSending.set(true);
        }

        @Override
        public void proxyToServerRequestSent() {
            proxyToServerRequestSent.set(true);
        }

        @Override
        public HttpObject serverToProxyResponse(HttpObject httpObject) {
            serverToProxyResponse.set(true);
            return httpObject;
        }

        @Override
        public void serverToProxyResponseTimedOut() {
            serverToProxyResponseTimedOut.set(true);
        }

        @Override
        public void serverToProxyResponseReceiving() {
            serverToProxyResponseReceiving.set(true);
        }

        @Override
        public void serverToProxyResponseReceived() {
            serverToProxyResponseReceived.set(true);
        }

        @Override
        public HttpObject proxyToClientResponse(HttpObject httpObject) {
            proxyToClientResponse.set(true);
            return httpObject;
        }

        @Override
        public void proxyToServerConnectionQueued() {
            proxyToServerConnectionQueued.set(true);
        }

        @Override
        public InetSocketAddress proxyToServerResolutionStarted(String resolvingServerHostAndPort) {
            proxyToServerResolutionStarted.set(true);
            return null;
        }

        @Override
        public void proxyToServerResolutionFailed(String hostAndPort) {
            proxyToServerResolutionFailed.set(true);
        }

        @Override
        public void proxyToServerResolutionSucceeded(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress) {
            proxyToServerResolutionSucceeded.set(true);
        }

        @Override
        public void proxyToServerConnectionStarted() {
            proxyToServerConnectionStarted.set(true);
        }

        @Override
        public void proxyToServerConnectionSSLHandshakeStarted() {
            proxyToServerConnectionSSLHandshakeStarted.set(true);
        }
    }
}
