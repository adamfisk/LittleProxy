package org.littleshoot.proxy;

import static org.junit.Assert.*;
import static org.littleshoot.proxy.TestUtils.*;

import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ProxyChainTest {

    private static final int WEB_SERVER_PORT = 20080;
    private static final int WEB_SERVER_SSL_PORT = 20081;
    private static final HttpHost WEB_SERVER_HOST = new HttpHost("127.0.0.1",
            WEB_SERVER_PORT);
    private static final HttpHost WEB_SERVER_SSL_HOST = new HttpHost(
            "127.0.0.1", WEB_SERVER_SSL_PORT, "https");
    private static final int PROXY_PORT = 8081;
    private static final String PROXY_HOST_AND_PORT = "127.0.0.1:8081";
    private static final int ANOTHER_PROXY_PORT = 8082;

    private Server webServer;
    private HttpProxyServer proxyServer;
    private HttpProxyServer anotherProxyServer;
    private HttpClient httpclient;

    @Before
    public void setUp() throws Exception {
        webServer = startWebServer(WEB_SERVER_PORT, WEB_SERVER_SSL_PORT);
        proxyServer = startProxyServer(PROXY_PORT);
        anotherProxyServer = startProxyServer(ANOTHER_PROXY_PORT,
                PROXY_HOST_AND_PORT);
    }

    @After
    public void tearDown() throws Exception {
        try {
            webServer.stop();
        } finally {
            try {
                anotherProxyServer.stop();
            } finally {
                proxyServer.stop();
            }
        }
    }

    @Test
    public void testSingleProxy() throws Exception {
        // Given
        httpclient = createProxiedHttpClient(PROXY_PORT);

        // When
        final HttpResponse response = httpclient.execute(WEB_SERVER_HOST,
                new HttpGet("/"));

        // Then
        assertEquals(HttpServletResponse.SC_OK, response.getStatusLine()
                .getStatusCode());
        assertNotNull(response.getFirstHeader("Via"));
    }

    @Test
    public void testChainedProxy() throws Exception {
        // Given
        httpclient = createProxiedHttpClient(ANOTHER_PROXY_PORT);

        // When
        final HttpResponse response = httpclient.execute(WEB_SERVER_HOST,
                new HttpGet("/"));

        // Then
        assertEquals(HttpServletResponse.SC_OK, response.getStatusLine()
                .getStatusCode());
        assertNotNull(response.getFirstHeader("Via"));
    }

    @Test
    public void testChainedProxySSL() throws Exception {
        // Given
        httpclient = createProxiedHttpClient(ANOTHER_PROXY_PORT, true);

        // When
        final HttpResponse response = httpclient.execute(WEB_SERVER_SSL_HOST,
                new HttpGet("/"));

        // Then
        assertEquals(HttpServletResponse.SC_OK, response.getStatusLine()
                .getStatusCode());
    }

    @Test
    public void testFallbackWhenChainedUnavailable() throws Exception {
        // Disable the chained proxy server
        proxyServer.stop();

        // Given
        httpclient = createProxiedHttpClient(ANOTHER_PROXY_PORT);

        // When
        final HttpResponse response = httpclient.execute(WEB_SERVER_HOST,
                new HttpGet("/"));

        // Then
        assertEquals(HttpServletResponse.SC_OK, response.getStatusLine()
                .getStatusCode());
        assertNotNull(response.getFirstHeader("Via"));
    }

}
