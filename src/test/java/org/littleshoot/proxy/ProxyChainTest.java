package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;
import static org.littleshoot.proxy.TestUtils.startProxyServer;
import static org.littleshoot.proxy.TestUtils.startWebServer;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.eclipse.jetty.server.Server;
import org.junit.Test;

public class ProxyChainTest {

    private static final int WEB_SERVER_PORT = 1080;
    private static final HttpHost WEB_SERVER_HOST = new HttpHost("localhost", WEB_SERVER_PORT);
    private static final int PROXY_PORT = 8081;
    
    private static final SocketAddress PROXY_HOST_AND_PORT = new InetSocketAddress("localhost", PROXY_PORT);
    private static final int ANOTHER_PROXY_PORT = 8082;

    private Server webServer;
    private HttpProxyServer proxyServer;
    private HttpProxyServer anotherProxyServer;
    private HttpClient httpclient;

    @Test public void testSingleProxy() throws Exception {
        // Given
        webServer = startWebServer(WEB_SERVER_PORT);
        proxyServer = startProxyServer(PROXY_PORT);
        httpclient = createProxiedHttpClient(PROXY_PORT);

        // When
        final HttpResponse response = httpclient.execute(WEB_SERVER_HOST, new HttpGet("/"));

        // Then
        assertEquals(HttpServletResponse.SC_OK, response.getStatusLine().getStatusCode());
        assertNotNull(response.getFirstHeader("Via"));

        webServer.stop();
        proxyServer.stop();
    }

    @Test public void testChainedProxy() throws Exception {
        // Given
        webServer = startWebServer(WEB_SERVER_PORT);
        proxyServer = startProxyServer(PROXY_PORT);
        anotherProxyServer = startProxyServer(ANOTHER_PROXY_PORT, PROXY_HOST_AND_PORT);
        httpclient = createProxiedHttpClient(ANOTHER_PROXY_PORT);

        // When
        final HttpResponse response = httpclient.execute(WEB_SERVER_HOST, new HttpGet("/"));

        // Then
        assertEquals(HttpServletResponse.SC_OK, response.getStatusLine().getStatusCode());
        assertNotNull(response.getFirstHeader("Via"));

        webServer.stop();
        anotherProxyServer.stop();
        proxyServer.stop();
    }

}
