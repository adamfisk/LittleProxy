package org.littleshoot.proxy;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HttpFilterTest {
    private Server webServer;
    private HttpProxyServer proxyServer;
    private int webServerPort;

    @Before
    public void setUp() throws Exception {
        webServer = new Server(0);
        webServer.start();
        webServerPort = TestUtils.findLocalHttpPort(webServer);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (webServer != null) {
                webServer.stop();
            }
        } finally {
            if (proxyServer != null) {
                proxyServer.stop();
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
            final Socket sock = new Socket();
            try {
                sock.connect(isa);
                break;
            } catch (final IOException e) {
                // Keep trying.
            } finally {
                IOUtils.closeQuietly(sock);
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
                new LinkedList<HttpRequest>();

        final AtomicInteger requestCount = new AtomicInteger(0);
        final AtomicLongArray proxyToServerRequestSendingNanos = new AtomicLongArray(new long[] { -1, -1, -1, -1, -1 });
        final AtomicLongArray proxyToServerRequestSentNanos = new AtomicLongArray(new long[] { -1, -1, -1,-1, -1 });
        final AtomicLongArray serverToProxyResponseReceivingNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray serverToProxyResponseReceivedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionQueuedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionStartedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionSucceededNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionStartedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionSSLHandshakeStartedNanos = new AtomicLongArray(new long[] {-1, -1, -1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionFailedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionSucceededNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });

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
                            if (httpRequest.getUri().equals(url2)) {
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
                            if (httpRequest.getUri().equals("/testing2")) {
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
                        if (originalRequest.getUri().contains("testing3")) {
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
                    public void serverToProxyResponseReceiving() {
                        serverToProxyResponseReceivingNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void serverToProxyResponseReceived() {
                        serverToProxyResponseReceivedNanos.set(requestCount.get(), now());
                    }

                    public HttpObject proxyToClientResponse(
                            HttpObject httpObject) {
                        if (originalRequest.getUri().contains("testing4")) {
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
                    public void proxyToServerConnectionSucceeded() {
                        proxyToServerConnectionSucceededNanos.set(requestCount.get(), now());
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

        org.apache.http.HttpResponse response1 = getResponse(url1);
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
        assertThat(proxyToServerConnectionStartedNanos.get(i), lessThan(proxyToServerConnectionSucceededNanos.get(i)));
        assertThat(proxyToServerConnectionSucceededNanos.get(i), lessThan(proxyToServerRequestSendingNanos.get(i)));
        assertThat(proxyToServerRequestSendingNanos.get(i), lessThan(proxyToServerRequestSentNanos.get(i)));
        assertThat(proxyToServerRequestSentNanos.get(i), lessThan(serverToProxyResponseReceivingNanos.get(i)));
        assertThat(serverToProxyResponseReceivingNanos.get(i), lessThan(serverToProxyResponseReceivedNanos.get(i)));

        // We just open a second connection here since reusing the original
        // connection is inconsistent.
        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response2 = getResponse(url2);
        Thread.sleep(500);

        assertEquals(403, response2.getStatusLine().getStatusCode());

        assertEquals(2, associatedRequests.size());
        assertEquals(2, shouldFilterCalls.get());
        assertEquals(2, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response3 = getResponse(url3);
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

        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        final HttpRequest third = associatedRequests.remove();

        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertEquals(url1, first.getUri());
        assertEquals(url2, second.getUri());
        assertEquals(url3, third.getUri());

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response4 = getResponse(url4);
        Thread.sleep(500);

        i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i), lessThan(proxyToServerResolutionStartedNanos.get(i)));
        assertThat(proxyToServerResolutionStartedNanos.get(i), lessThan(proxyToServerResolutionSucceededNanos.get(i)));
        assertThat(proxyToServerResolutionSucceededNanos.get(i), lessThan(proxyToServerConnectionStartedNanos.get(i)));
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos.get(i));
        assertEquals(-1, proxyToServerConnectionFailedNanos.get(i));
        assertThat(proxyToServerConnectionStartedNanos.get(i), lessThan(proxyToServerConnectionSucceededNanos.get(i)));
        assertThat(proxyToServerConnectionSucceededNanos.get(i), lessThan(proxyToServerRequestSendingNanos.get(i)));
        assertThat(proxyToServerRequestSendingNanos.get(i), lessThan(proxyToServerRequestSentNanos.get(i)));
        assertThat(proxyToServerRequestSentNanos.get(i), lessThan(serverToProxyResponseReceivingNanos.get(i)));
        assertThat(serverToProxyResponseReceivingNanos.get(i), lessThan(serverToProxyResponseReceivedNanos.get(i)));

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response5 = getResponse(url5);

        assertEquals(403, response4.getStatusLine().getStatusCode());
        assertEquals(403, response5.getStatusLine().getStatusCode());
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

        getResponse("http://localhost:" + webServerPort + "/");

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

        getResponse("http://www.doesnotexist/some-resource");

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertFalse("proxyToServerResolutionSucceeded method was called but should not have been", filter.isProxyToServerResolutionSucceededInvoked());
        assertTrue("proxyToServerResolutionFailed method was not called", filter.isProxyToServerResolutionFailedInvoked());

        assertTrue("Expected filter method to be called", filter.isClientToProxyRequestInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerConnectionQueuedInvoked());
        assertTrue("Expected filter method to be called", filter.isProxyToServerResolutionStartedInvoked());

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
        assertFalse("Expected filter method to not be called", filter.isProxyToClientResponseInvoked());
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
        getResponse("http://localhost:0/some-resource");

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

        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSendingInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerRequestSentInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerConnectionSSLHandshakeStartedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToServerResolutionFailedInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivingInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseInvoked());
        assertFalse("Expected filter method to not be called", filter.isServerToProxyResponseReceivedInvoked());
        assertFalse("Expected filter method to not be called", filter.isProxyToClientResponseInvoked());
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
        postToServer("http://localhost:" + webServerPort + "/", 50000);

        assertTrue("proxyToServerRequest callback was not invoked for LastHttpContent for chunked POST", lastHttpContentProcessed.get());
        assertTrue("proxyToServerRequestSent callback was not invoked for chunked POST", requestSentCallbackInvoked.get());

        // test with a non-payload-bearing GET request.
        lastHttpContentProcessed.set(false);
        requestSentCallbackInvoked.set(false);

        getResponse("http://localhost:" + webServerPort + "/");
        Thread.sleep(500);

        assertTrue("proxyToServerRequest callback was not invoked for LastHttpContent for GET", lastHttpContentProcessed.get());
        assertTrue("proxyToServerRequestSent callback was not invoked for GET", requestSentCallbackInvoked.get());
    }

    private DefaultHttpClient getDefaultHttpClient() {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpHost proxy = new HttpHost("127.0.0.1", proxyServer.getListenAddress().getPort(), "http");
        httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);

        return httpClient;
    }

    private org.apache.http.HttpResponse getResponse(final String url)
            throws Exception {
        final DefaultHttpClient http = getDefaultHttpClient();

        final HttpGet get = new HttpGet(url);

        return getHttpResponse(http, get);
    }

    private org.apache.http.HttpResponse postToServer(String url, int postSizeInBytes) throws Exception {
        DefaultHttpClient httpClient = getDefaultHttpClient();

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < postSizeInBytes; i++) {
            sb.append('q');
        }

        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(sb.toString()));

        return getHttpResponse(httpClient, post);
    }

    private org.apache.http.HttpResponse getHttpResponse(DefaultHttpClient httpClient, HttpUriRequest get) throws IOException {
        final org.apache.http.HttpResponse hr = httpClient.execute(get);
        final HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        httpClient.getConnectionManager().shutdown();
        return hr;
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

        @Override
        public void proxyToServerConnectionFailed() {
            proxyToServerConnectionFailed.set(true);
        }

        @Override
        public void proxyToServerConnectionSucceeded() {
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
