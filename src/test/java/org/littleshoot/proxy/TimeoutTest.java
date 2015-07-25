package org.littleshoot.proxy;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.Delay;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class TimeoutTest {
    private ClientAndServer mockServer;
    private int mockServerPort;

    private HttpProxyServer proxyServer;

    @Before
    public void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getPort();
    }

    @After
    public void tearDown() {
        try {
            if (mockServer != null) {
                mockServer.stop();
            }
        } finally {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        }
    }

    @Test
    public void testIdleConnectionTimeout() throws IOException {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withIdleConnectionTimeout(1)
                .start();

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/idleconnection"),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withDelay(new Delay(TimeUnit.SECONDS, 5))
                );

        DefaultHttpClient httpClient = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", proxyServer.getListenAddress().getPort(), "http");
        httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);

        long start = System.nanoTime();
        HttpGet get = new HttpGet("http://127.0.0.1:" + mockServerPort + "/idleconnection");
        long stop = System.nanoTime();

        HttpResponse response = httpClient.execute(get);
        EntityUtils.consumeQuietly(response.getEntity());

        assertEquals("Expected to receive an HTTP 504 (Gateway Timeout) response after proxy did not receive a response within 1 second", 504, response.getStatusLine().getStatusCode());
        assertThat("Expected idle connection timeout to happen after approximately 1 second",
                TimeUnit.MILLISECONDS.convert(stop - start, TimeUnit.NANOSECONDS), lessThan(2000L));
    }

    @Test
    public void testConnectionTimeout() throws IOException {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withConnectTimeout(1000)
                .start();

        DefaultHttpClient httpClient = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", proxyServer.getListenAddress().getPort(), "http");
        httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);

        HttpGet get = new HttpGet("http://1.2.3.4:53540");

        long start = System.nanoTime();
        HttpResponse response = httpClient.execute(get);
        long stop = System.nanoTime();

        EntityUtils.consumeQuietly(response.getEntity());

        assertEquals("Expected to receive an HTTP 502 (Bad Gateway) response after proxy could not connect within 1 second", 502, response.getStatusLine().getStatusCode());
        assertThat("Expected connection timeout to happen after approximately 1 second",
                TimeUnit.MILLISECONDS.convert(stop - start, TimeUnit.NANOSECONDS), lessThan(2000L));
    }
}
