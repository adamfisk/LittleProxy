package org.littleshoot.proxy;

import io.netty.handler.codec.http.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.HttpClientUtil;

import javax.net.ssl.SSLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.*;

/**
 * This class tests direct requests to the proxy server, which causes endless
 * loops (#205).
 */
public class DirectRequestTest {

    private HttpProxyServer proxyServer;

    @Before
    public void setUp() {
        proxyServer = null;
    }

    @After
    public void tearDown() {
        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    @Test(timeout = 5000)
    public void testAnswerBadRequestInsteadOfEndlessLoop() {

        startProxyServer();

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = HttpClientUtil.performHttpGet("http://127.0.0.1:" + proxyPort + "/directToProxy", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertEquals("Expected to receive an HTTP 400 from the server", 400, statusCode);
    }

    @Test(timeout = 5000)
    public void testAnswerFromFilterShouldBeServed() {

        startProxyServerWithFilterAnsweringStatusCode(403);

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = HttpClientUtil.performHttpGet("http://localhost:" + proxyPort + "/directToProxy", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertEquals("Expected to receive an HTTP 403 from the server", 403, statusCode);
    }

    private void startProxyServerWithFilterAnsweringStatusCode(int statusCode) {
        final HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);
        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();
    }

    @Test(timeout = 5000)
    public void testHttpsShouldCancelConnection() {
        startProxyServer();

        int proxyPort = proxyServer.getListenAddress().getPort();


        try {
            HttpClientUtil.performHttpGet("https://localhost:" + proxyPort + "/directToProxy", proxyServer);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause();
            assertThat("Expected an SSL exception when attempting to perform an HTTPS GET directly to the proxy", cause, instanceOf(SSLException.class));
        }
    }

    @Test(timeout = 5000)
    public void testAllowRequestToOriginServerWithOverride() {
        // verify that the filter is hit twice: first, on the request from the client, without a Via header; and second, when the proxy
        // forwards the request to itself
        final AtomicBoolean receivedRequestWithoutVia = new AtomicBoolean();

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withAllowRequestToOriginServer(true)
                .withProxyAlias("testAllowRequestToOriginServerWithOverride")
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    HttpRequest request = (HttpRequest) httpObject;
                                    String viaHeader = request.headers().get(HttpHeaderNames.VIA);
                                    if (viaHeader != null && viaHeader.contains("testAllowRequestToOriginServerWithOverride")) {
                                        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
                                    } else {
                                        receivedRequestWithoutVia.set(true);
                                    }
                                }
                                return null;
                            }
                        };
                    }
                })
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        org.apache.http.HttpResponse response = HttpClientUtil.performHttpGet("http://localhost:" + proxyPort + "/originrequest", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertEquals("Expected to receive a 204 response from the filter", 204, statusCode);

        assertTrue("Expected to receive a request from the client without a Via header", receivedRequestWithoutVia.get());
    }

    private void startProxyServer() {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
    }

}
