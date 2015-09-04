package org.littleshoot.proxy;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.HttpClientUtil;

import javax.net.ssl.SSLException;

import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * This class tests direct requests to the proxy server, which causes endless
 * loops (#205).
 */
public class DirectRequestTest {

    private HttpProxyServer proxyServer;

    @Before
    public void setUp() throws Exception {
        proxyServer = null;
    }

    @After
    public void tearDown() throws Exception {
        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    @Test(timeout = 5000)
    public void testAnswerBadRequestInsteadOfEndlessLoop() throws Exception {

        startProxyServer();

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = HttpClientUtil.performHttpGet("http://127.0.0.1:" + proxyPort + "/directToProxy", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertEquals("Expected to receive an HTTP 400 from the server", 400, statusCode);
    }

    @Test(timeout = 5000)
    public void testAnswerFromFilterShouldBeServed() throws Exception {

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

    private void startProxyServer() {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
    }

}
