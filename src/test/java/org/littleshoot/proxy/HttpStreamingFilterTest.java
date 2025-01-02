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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;

public final class HttpStreamingFilterTest {
    private Server webServer;
    private int webServerPort = -1;
    private HttpProxyServer proxyServer;

    private final AtomicInteger numberOfInitialRequestsFiltered = new AtomicInteger(0);
    private final AtomicInteger numberOfSubsequentChunksFiltered = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        numberOfInitialRequestsFiltered.set(0);
        numberOfSubsequentChunksFiltered.set(0);

        webServer = TestUtils.startWebServer(true);
        webServerPort = TestUtils.findLocalHttpPort(webServer);

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @NonNull
                    @Override
                    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Nullable
                            @Override
                            public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    numberOfInitialRequestsFiltered.incrementAndGet();
                                } else {
                                    numberOfSubsequentChunksFiltered.incrementAndGet();
                                }
                                return null;
                            }
                        };
                    }
                })
                .start();
    }

    @AfterEach
    void tearDown() throws Exception {
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
        Arrays.fill(largeData, (byte) 1);

        final HttpPost request = new HttpPost("/");
        request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);

        final ByteArrayEntity entity = new ByteArrayEntity(largeData);
        entity.setChunked(true);
        request.setEntity(entity);

        try (CloseableHttpClient httpClient = createProxiedHttpClient(proxyServer.getListenAddress().getPort())) {

            final org.apache.http.HttpResponse response = httpClient.execute(
              new HttpHost("127.0.0.1",
                webServerPort), request);

            assertThat(EntityUtils.toString(response.getEntity())).isEqualTo("Received 20000 bytes\n");

            assertThat(numberOfInitialRequestsFiltered.get()).as("Filter should have seen only 1 HttpRequest").isEqualTo(1);
            assertThat(numberOfSubsequentChunksFiltered.get()).as("Filter should have seen 1 or more chunks").isGreaterThanOrEqualTo(1);
        }
    }
}
