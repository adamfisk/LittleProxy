package org.littleshoot.proxy;

import static org.junit.Assert.*;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

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

public class HttpFilterTest {

    private static final int PROXY_PORT = 8923;
    private static final int WEB_SERVER_PORT = 8924;

    @Test
    public void testFiltering() throws Exception {

        final AtomicInteger shouldFilterCalls = new AtomicInteger(0);
        final AtomicInteger filterResponseCalls = new AtomicInteger(0);
        final AtomicInteger fullHttpRequestsReceived = new AtomicInteger(0);
        final AtomicInteger fullHttpResponsesReceived = new AtomicInteger(0);
        final Queue<HttpRequest> associatedRequests =
                new LinkedList<HttpRequest>();

        final String url1 = "http://localhost:8924/";
        final String url2 = "http://localhost:8924/testing";
        final String url3 = "http://localhost:8924/testing2";
        final String url4 = "http://localhost:8924/testing3";
        final String url5 = "http://localhost:8924/testing4";
        final HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                shouldFilterCalls.incrementAndGet();
                associatedRequests.add(originalRequest);

                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse requestPre(HttpObject httpObject) {
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
                    public HttpResponse requestPost(HttpObject httpObject) {
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

                    public HttpObject responsePre(HttpObject httpObject) {
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
                    };

                    public HttpObject responsePost(HttpObject httpObject) {
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
                    };
                };
            };

            public int getMaximumRequestBufferSizeInBytes() {
                return 1024 * 1024;
            };

            public int getMaximumResponseBufferSizeInBytes() {
                return 1024 * 1024;
            };
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

        // We just open a second connection here since reusing the original
        // connection is inconsistent.
        org.apache.http.HttpResponse response2 = getResponse(url2);
        assertEquals(403, response2.getStatusLine().getStatusCode());

        assertEquals(2, associatedRequests.size());
        assertEquals(2, shouldFilterCalls.get());
        assertEquals(2, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        org.apache.http.HttpResponse response3 = getResponse(url3);
        assertEquals(403, response3.getStatusLine().getStatusCode());

        assertEquals(3, associatedRequests.size());
        assertEquals(3, shouldFilterCalls.get());
        assertEquals(3, fullHttpRequestsReceived.get());
        assertEquals(1, fullHttpResponsesReceived.get());
        assertEquals(1, filterResponseCalls.get());

        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        final HttpRequest third = associatedRequests.remove();

        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertEquals(url1, first.getUri());
        assertEquals(url2, second.getUri());
        assertEquals(url3, third.getUri());

        org.apache.http.HttpResponse response4 = getResponse(url4);
        org.apache.http.HttpResponse response5 = getResponse(url5);
        
        assertEquals(403, response4.getStatusLine().getStatusCode());
        assertEquals(403, response5.getStatusLine().getStatusCode());
        
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
