package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.assertEquals;

public class HttpStreamingFilterTest {
    private Server webServer;
    private int webServerPort = -1;
    private HttpProxyServer proxyServer;

    private final AtomicInteger numberOfInitialRequestsFiltered = new AtomicInteger(
            0);
    private final AtomicInteger numberOfSubsequentChunksFiltered = new AtomicInteger(
            0);

    @Before
    public void setUp() {
        numberOfInitialRequestsFiltered.set(0);
        numberOfSubsequentChunksFiltered.set(0);

        webServer = TestUtils.startWebServer(true);
        webServerPort = TestUtils.findLocalHttpPort(webServer);

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse clientToProxyRequest(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    numberOfInitialRequestsFiltered
                                            .incrementAndGet();
                                } else {
                                    numberOfSubsequentChunksFiltered
                                            .incrementAndGet();
                                }
                                return null;
                            }
                        };
                    }
                })
                .start();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        } finally {
            if (webServer != null) {
                webServer.stop();
            }
        }
    }

    @Test
    public void testFiltering() throws Exception {
        // Set up some large data to make sure we get chunked encoding on post
        byte[] largeData = new byte[20000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = 1;
        }

        final HttpPost request = new HttpPost("/");
        request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);

        final ByteArrayEntity entity = new ByteArrayEntity(largeData);
        entity.setChunked(true);
        request.setEntity(entity);

        CloseableHttpClient httpClient = TestUtils.createProxiedHttpClient(
                proxyServer.getListenAddress().getPort());

        final org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        webServerPort), request);

        assertEquals("Received 20000 bytes\n",
                EntityUtils.toString(response.getEntity()));

        assertEquals("Filter should have seen only 1 HttpRequest", 1,
                numberOfInitialRequestsFiltered.get());
        assertThat("Filter should have seen 1 or more chunks",
                numberOfSubsequentChunksFiltered.get(), greaterThanOrEqualTo(1));
    }
}
