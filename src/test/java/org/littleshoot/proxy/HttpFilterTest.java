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
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HttpFilterTest {

    private static final int PROXY_PORT = 8923;
    private static final int WEB_SERVER_PORT = 8924;

    private long now() {
        // using nanoseconds instead of milliseconds, since it is extremely unlikely that any two callbacks would be invoked in the same nanosecond,
        // even on very fast hardware
        return System.nanoTime();
    }

    private Server webServer;

    @Before
    public void setUp() throws Exception {
        webServer = new Server(WEB_SERVER_PORT);
        webServer.start();
    }

    @After
    public void tearDown() throws Exception {
        if (webServer != null) {
            webServer.stop();
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
        final long[] proxyToServerRequestSendingNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerRequestSentNanos = new long[] { -1, -1, -1,
                -1, -1 };
        final long[] serverToProxyResponseReceivingNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] serverToProxyResponseReceivedNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerConnectionQueuedNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerResolutionStartedNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerResolutionSucceededNanos = new long[] { -1,
                -1, -1, -1, -1 };
        final long[] proxyToServerConnectionStartedNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerConnectionSSLHandshakeStartedNanos = new long[] {
                -1, -1, -1, -1, -1 };
        final long[] proxyToServerConnectionFailedNanos = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerConnectionSucceededNanos = new long[] { -1,
                -1, -1, -1, -1 };

        final String url1 = "http://localhost:" + WEB_SERVER_PORT + "/";
        final String url2 = "http://localhost:" + WEB_SERVER_PORT + "/testing";
        final String url3 = "http://localhost:" + WEB_SERVER_PORT + "/testing2";
        final String url4 = "http://localhost:" + WEB_SERVER_PORT + "/testing3";
        final String url5 = "http://localhost:" + WEB_SERVER_PORT + "/testing4";
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
                        proxyToServerRequestSendingNanos[requestCount.get()] = now();
                    }

                    @Override
                    public void proxyToServerRequestSent() {
                        proxyToServerRequestSentNanos[requestCount.get()] = now();
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
                        serverToProxyResponseReceivingNanos[requestCount.get()] = now();
                    }

                    @Override
                    public void serverToProxyResponseReceived() {
                        serverToProxyResponseReceivedNanos[requestCount.get()] = now();
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
                        proxyToServerConnectionQueuedNanos[requestCount.get()] = now();
                    }

                    @Override
                    public InetSocketAddress proxyToServerResolutionStarted(
                            String resolvingServerHostAndPort) {
                        proxyToServerResolutionStartedNanos[requestCount.get()] = now();
                        return super
                                .proxyToServerResolutionStarted(resolvingServerHostAndPort);
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(
                            String serverHostAndPort,
                            InetSocketAddress resolvedRemoteAddress) {
                        proxyToServerResolutionSucceededNanos[requestCount
                                .get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionStarted() {
                        proxyToServerConnectionStartedNanos[requestCount.get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionSSLHandshakeStarted() {
                        proxyToServerConnectionSSLHandshakeStartedNanos[requestCount
                                .get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionFailed() {
                        proxyToServerConnectionFailedNanos[requestCount.get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionSucceeded() {
                        proxyToServerConnectionSucceededNanos[requestCount
                                .get()] = now();
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

        final HttpProxyServer server = getHttpProxyServer(filtersSource);

        org.apache.http.HttpResponse response1 = getResponse(url1);
        requestCount.incrementAndGet();
        assertTrue(
                "Response should have included the custom header from our pre filter",
                "1".equals(response1.getFirstHeader("Header-Pre")
                        .getValue()));
        assertTrue(
                "Response should have included the custom header from our post filter",
                "2".equals(response1.getFirstHeader("Header-Post")
                        .getValue()));

        assertEquals(1, associatedRequests.size());
        assertEquals(1, shouldFilterCalls.get());
        assertEquals(1, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        int i = 0;
        assertTrue(proxyToServerConnectionQueuedNanos[i] < proxyToServerResolutionStartedNanos[i]);
        assertTrue(proxyToServerResolutionStartedNanos[i] < proxyToServerResolutionSucceededNanos[i]);
        assertTrue(proxyToServerResolutionSucceededNanos[i] < proxyToServerConnectionStartedNanos[i]);
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos[i]);
        assertEquals(-1, proxyToServerConnectionFailedNanos[i]);
        assertTrue(proxyToServerConnectionStartedNanos[i] < proxyToServerConnectionSucceededNanos[i]);
        assertTrue(proxyToServerConnectionSucceededNanos[i] < proxyToServerRequestSendingNanos[i]);
        assertTrue(proxyToServerRequestSendingNanos[i] < proxyToServerRequestSentNanos[i]);
        assertTrue(proxyToServerRequestSentNanos[i] < serverToProxyResponseReceivingNanos[i]);
        assertTrue(serverToProxyResponseReceivingNanos[i] < serverToProxyResponseReceivedNanos[i]);

        // We just open a second connection here since reusing the original
        // connection is inconsistent.
        org.apache.http.HttpResponse response2 = getResponse(url2);
        requestCount.incrementAndGet();
        assertEquals(403, response2.getStatusLine().getStatusCode());

        assertEquals(2, associatedRequests.size());
        assertEquals(2, shouldFilterCalls.get());
        assertEquals(2, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        org.apache.http.HttpResponse response3 = getResponse(url3);
        requestCount.incrementAndGet();
        assertEquals(403, response3.getStatusLine().getStatusCode());

        assertEquals(3, associatedRequests.size());
        assertEquals(3, shouldFilterCalls.get());
        assertEquals(3, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        i = 2;
        assertTrue(proxyToServerConnectionQueuedNanos[i] < proxyToServerResolutionStartedNanos[i]);
        assertTrue(proxyToServerResolutionStartedNanos[i] < proxyToServerResolutionSucceededNanos[i]);
        assertEquals(-1, proxyToServerConnectionStartedNanos[i]);
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos[i]);
        assertEquals(-1, proxyToServerConnectionFailedNanos[i]);
        assertEquals(-1, proxyToServerConnectionSucceededNanos[i]);
        assertEquals(-1, proxyToServerRequestSendingNanos[i]);
        assertEquals(-1, proxyToServerRequestSentNanos[i]);
        assertEquals(-1, serverToProxyResponseReceivingNanos[i]);
        assertEquals(-1, serverToProxyResponseReceivedNanos[i]);

        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        final HttpRequest third = associatedRequests.remove();

        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertEquals(url1, first.getUri());
        assertEquals(url2, second.getUri());
        assertEquals(url3, third.getUri());

        org.apache.http.HttpResponse response4 = getResponse(url4);
        i = 3;
        assertTrue(proxyToServerConnectionQueuedNanos[i] < proxyToServerResolutionStartedNanos[i]);
        assertTrue(proxyToServerResolutionStartedNanos[i] < proxyToServerResolutionSucceededNanos[i]);
        assertTrue(proxyToServerResolutionSucceededNanos[i] < proxyToServerConnectionStartedNanos[i]);
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedNanos[i]);
        assertEquals(-1, proxyToServerConnectionFailedNanos[i]);
        assertTrue(proxyToServerConnectionStartedNanos[i] < proxyToServerConnectionSucceededNanos[i]);
        assertTrue(proxyToServerConnectionSucceededNanos[i] < proxyToServerRequestSendingNanos[i]);
        assertTrue(proxyToServerRequestSendingNanos[i] < proxyToServerRequestSentNanos[i]);
        assertTrue(proxyToServerRequestSentNanos[i] < serverToProxyResponseReceivingNanos[i]);
        assertTrue(serverToProxyResponseReceivingNanos[i] < serverToProxyResponseReceivedNanos[i]);

        org.apache.http.HttpResponse response5 = getResponse(url5);

        assertEquals(403, response4.getStatusLine().getStatusCode());
        assertEquals(403, response5.getStatusLine().getStatusCode());

        server.stop();
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
                        return InetSocketAddress.createUnresolved("localhost", WEB_SERVER_PORT);
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress) {
                        assertFalse("expected to receive a resolved InetSocketAddress", resolvedRemoteAddress.isUnresolved());
                        resolutionSucceeded.set(true);
                    }
                };
            }
        };

        final HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_PORT)
                .withFiltersSource(filtersSource)
                .start();

        getResponse("http://localhost:" + WEB_SERVER_PORT + "/");

        assertTrue("proxyToServerResolutionSucceeded method was not called", resolutionSucceeded.get());

        server.stop();
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

        final HttpProxyServer server = getHttpProxyServer(filtersSource);

        // test with a POST request with a payload. post a large amount of data, to force chunked content.
        postToServer("http://localhost:" + WEB_SERVER_PORT + "/", 50000);


        assertTrue("proxyToServerRequest callback was not invoked for LastHttpContent for chunked POST", lastHttpContentProcessed.get());
        assertTrue("proxyToServerRequestSent callback was not invoked for chunked POST", requestSentCallbackInvoked.get());

        // test with a non-payload-bearing GET request.
        lastHttpContentProcessed.set(false);
        requestSentCallbackInvoked.set(false);

        getResponse("http://localhost:" + WEB_SERVER_PORT + "/");

        assertTrue("proxyToServerRequest callback was not invoked for LastHttpContent for GET", lastHttpContentProcessed.get());
        assertTrue("proxyToServerRequestSent callback was not invoked for GET", requestSentCallbackInvoked.get());

        server.stop();
    }

    private HttpProxyServer getHttpProxyServer(HttpFiltersSource filtersSource) throws InterruptedException {
        final HttpProxyServer server = DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_PORT)
                .withFiltersSource(filtersSource)
                .start();
        boolean connected = false;
        final InetSocketAddress isa = new InetSocketAddress("127.0.0.1",
                PROXY_PORT);
        while (!connected) {
            final Socket sock = new Socket();
            try {
                sock.connect(isa);
                break;
            } catch (final IOException e) {
                // Keep trying.
            } finally {
                IOUtils.closeQuietly(sock);
            }
            Thread.sleep(50);
        }
        return server;
    }

    private DefaultHttpClient getDefaultHttpClient() {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpHost proxy = new HttpHost("127.0.0.1", PROXY_PORT, "http");
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
}
