package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.util.concurrent.atomic.AtomicInteger;

public class HttpStreamingFilterTest {

    private static final int PROXY_PORT = 8925;
    private static final int WEB_SERVER_PORT = 8926;

    private Server webServer;
    private HttpProxyServer proxyServer;
    private final AtomicInteger numberOfInitialRequestsFiltered = new AtomicInteger(
            0);
    private final AtomicInteger numberOfSubsequentChunksFiltered = new AtomicInteger(
            0);

    @Before
    public void setUp() throws Exception {
        numberOfInitialRequestsFiltered.set(0);
        numberOfSubsequentChunksFiltered.set(0);

        webServer = TestUtils.startWebServer(WEB_SERVER_PORT);

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_PORT)
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
                    };
                })
                .start();
    }

    @After
    public void tearDown() throws Exception {
        try {
            proxyServer.stop();
        } finally {
            webServer.stop();
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
        request.getParams().setParameter(
                CoreConnectionPNames.CONNECTION_TIMEOUT, 5000);
        // request.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT,
        // 15000);
        final ByteArrayEntity entity = new ByteArrayEntity(largeData);
        entity.setChunked(true);
        request.setEntity(entity);

        DefaultHttpClient httpClient = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", PROXY_PORT, "http");
        httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
                proxy);
        final org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        WEB_SERVER_PORT), request);

        Assert.assertEquals("Received 20000 bytes\n",
                EntityUtils.toString(response.getEntity()));

        Assert.assertEquals("Filter should have seen only 1 HttpRequest", 1,
                numberOfInitialRequestsFiltered.get());
        Assert.assertTrue("Filter should have seen 1 or more chunks",
                numberOfSubsequentChunksFiltered.get() >= 1);
    }
}
