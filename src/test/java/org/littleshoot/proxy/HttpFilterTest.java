package org.littleshoot.proxy;

import io.netty.handler.codec.http.*;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Date;
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
        return new Date().getTime();
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
        final long[] proxyToServerRequestSendingMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerRequestSentMills = new long[] { -1, -1, -1,
                -1, -1 };
        final long[] serverToProxyResponseReceivingMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] serverToProxyResponseReceivedMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerConnectionQueuedMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerResolutionStartedMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerResolutionSucceededMills = new long[] { -1,
                -1, -1, -1, -1 };
        final long[] proxyToServerConnectionStartedMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerConnectionSSLHandshakeStartedMills = new long[] {
                -1, -1, -1, -1, -1 };
        final long[] proxyToServerConnectionFailedMills = new long[] { -1, -1,
                -1, -1, -1 };
        final long[] proxyToServerConnectionSucceededMills = new long[] { -1,
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
                        proxyToServerRequestSendingMills[requestCount.get()] = now();
                    }

                    @Override
                    public void proxyToServerRequestSent() {
                        proxyToServerRequestSentMills[requestCount.get()] = now();
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
                        serverToProxyResponseReceivingMills[requestCount.get()] = now();
                    }

                    @Override
                    public void serverToProxyResponseReceived() {
                        serverToProxyResponseReceivedMills[requestCount.get()] = now();
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
                        proxyToServerConnectionQueuedMills[requestCount.get()] = now();
                    }

                    @Override
                    public InetSocketAddress proxyToServerResolutionStarted(
                            String resolvingServerHostAndPort) {
                        proxyToServerResolutionStartedMills[requestCount.get()] = now();
                        return super
                                .proxyToServerResolutionStarted(resolvingServerHostAndPort);
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(
                            String serverHostAndPort,
                            InetSocketAddress resolvedRemoteAddress) {
                        proxyToServerResolutionSucceededMills[requestCount
                                .get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionStarted() {
                        proxyToServerConnectionStartedMills[requestCount.get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionSSLHandshakeStarted() {
                        proxyToServerConnectionSSLHandshakeStartedMills[requestCount
                                .get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionFailed() {
                        proxyToServerConnectionFailedMills[requestCount.get()] = now();
                    }

                    @Override
                    public void proxyToServerConnectionSucceeded() {
                        proxyToServerConnectionSucceededMills[requestCount
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

        final Server webServer = new Server(WEB_SERVER_PORT);
        webServer.start();

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
        assertTrue(proxyToServerConnectionQueuedMills[i] <= proxyToServerResolutionStartedMills[i]);
        assertTrue(proxyToServerResolutionStartedMills[i] <= proxyToServerResolutionSucceededMills[i]);
        assertTrue(proxyToServerResolutionSucceededMills[i] <= proxyToServerConnectionStartedMills[i]);
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedMills[i]);
        assertEquals(-1, proxyToServerConnectionFailedMills[i]);
        assertTrue(proxyToServerConnectionStartedMills[i] <= proxyToServerConnectionSucceededMills[i]);
        assertTrue(proxyToServerConnectionSucceededMills[i] <= proxyToServerRequestSendingMills[i]);
        assertTrue(proxyToServerRequestSendingMills[i] <= proxyToServerRequestSentMills[i]);
        assertTrue(proxyToServerRequestSentMills[i] <= serverToProxyResponseReceivingMills[i]);
        assertTrue(serverToProxyResponseReceivingMills[i] <= serverToProxyResponseReceivedMills[i]);

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
        assertTrue(proxyToServerConnectionQueuedMills[i] <= proxyToServerResolutionStartedMills[i]);
        assertTrue(proxyToServerResolutionStartedMills[i] <= proxyToServerResolutionSucceededMills[i]);
        assertEquals(-1, proxyToServerConnectionStartedMills[i]);
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedMills[i]);
        assertEquals(-1, proxyToServerConnectionFailedMills[i]);
        assertEquals(-1, proxyToServerConnectionSucceededMills[i]);
        assertEquals(-1, proxyToServerRequestSendingMills[i]);
        assertEquals(-1, proxyToServerRequestSentMills[i]);
        assertEquals(-1, serverToProxyResponseReceivingMills[i]);
        assertEquals(-1, serverToProxyResponseReceivedMills[i]);

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
        assertTrue(proxyToServerConnectionQueuedMills[i] <= proxyToServerResolutionStartedMills[i]);
        assertTrue(proxyToServerResolutionStartedMills[i] <= proxyToServerResolutionSucceededMills[i]);
        assertTrue(proxyToServerResolutionSucceededMills[i] <= proxyToServerConnectionStartedMills[i]);
        assertEquals(-1, proxyToServerConnectionSSLHandshakeStartedMills[i]);
        assertEquals(-1, proxyToServerConnectionFailedMills[i]);
        assertTrue(proxyToServerConnectionStartedMills[i] <= proxyToServerConnectionSucceededMills[i]);
        assertTrue(proxyToServerConnectionSucceededMills[i] <= proxyToServerRequestSendingMills[i]);
        assertTrue(proxyToServerRequestSendingMills[i] <= proxyToServerRequestSentMills[i]);
        assertTrue(proxyToServerRequestSentMills[i] <= serverToProxyResponseReceivingMills[i]);
        assertTrue(serverToProxyResponseReceivingMills[i] <= serverToProxyResponseReceivedMills[i]);

        org.apache.http.HttpResponse response5 = getResponse(url5);

        assertEquals(403, response4.getStatusLine().getStatusCode());
        assertEquals(403, response5.getStatusLine().getStatusCode());

        webServer.stop();
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

        final Server webServer = new Server(WEB_SERVER_PORT);
        webServer.start();

        org.apache.http.HttpResponse response1 = getResponse("http://localhost:" + WEB_SERVER_PORT + "/");

        assertTrue("proxyToServerResolutionSucceeded method was not called", resolutionSucceeded.get());

        webServer.stop();
        server.stop();
    }

    private org.apache.http.HttpResponse getResponse(final String url)
            throws Exception {
        final DefaultHttpClient http = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", PROXY_PORT, "http");
        http.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        final HttpGet get = new HttpGet(url);
        final org.apache.http.HttpResponse hr = http.execute(get);
        final HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        http.getConnectionManager().shutdown();
        return hr;
    }

}
