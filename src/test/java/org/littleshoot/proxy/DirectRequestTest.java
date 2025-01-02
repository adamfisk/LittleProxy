package org.littleshoot.proxy;

import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import javax.net.ssl.SSLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.littleshoot.proxy.test.HttpClientUtil.performHttpGet;
import static org.littleshoot.proxy.test.HttpClientUtil.performLocalHttpGet;

/**
 * This class tests direct requests to the proxy server, which causes endless
 * loops (#205).
 */
@NullMarked
public final class DirectRequestTest {

    @Nullable
    private HttpProxyServer proxyServer;

    @AfterEach
    void tearDown() {
        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    @Test
    @Timeout(5)
    public void testAnswerBadRequestInsteadOfEndlessLoop() {

        HttpProxyServer proxyServer = startProxyServer();

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = performHttpGet("http://127.0.0.1:" + proxyPort + "/directToProxy", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertThat(statusCode).as("Expected to receive an HTTP 400 from the server").isEqualTo(400);
    }

    @Test
    @Timeout(5)
    public void testAnswerFromFilterShouldBeServed() {

        HttpProxyServer proxyServer = startProxyServerWithFilterAnsweringStatusCode(403);

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = performLocalHttpGet(proxyPort, "/directToProxy", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertThat(statusCode).as("Expected to receive an HTTP 403 from the server").isEqualTo(403);
    }

    private HttpProxyServer startProxyServerWithFilterAnsweringStatusCode(int statusCode) {
        final HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);
        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, status);
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();
        return proxyServer;
    }

    @Test
    @Timeout(5)
    public void testHttpsShouldCancelConnection() {
        HttpProxyServer proxyServer = startProxyServer();

        int proxyPort = proxyServer.getListenAddress().getPort();

        assertThatThrownBy(() -> performHttpGet("https://localhost:" + proxyPort + "/directToProxy", proxyServer))
          .isInstanceOf(RuntimeException.class)
          .cause()
          .as("Expected an SSL exception when attempting to perform an HTTPS GET directly to the proxy")
          .isInstanceOf(SSLException.class);
    }

    @Test
    @Timeout(5)
    public void testAllowRequestToOriginServerWithOverride() {
        // verify that the filter is hit twice: first, on the request from the client, without a Via header; and second, when the proxy
        // forwards the request to itself
        final AtomicBoolean receivedRequestWithoutVia = new AtomicBoolean();

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withAllowRequestToOriginServer(true)
                .withProxyAlias("testAllowRequestToOriginServerWithOverride")
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @NonNull
                    @Override
                    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Nullable
                            @Override
                            public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
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

        org.apache.http.HttpResponse response = performLocalHttpGet(proxyPort, "/originrequest", proxyServer);
        int statusCode = response.getStatusLine().getStatusCode();

        assertThat(statusCode).as("Expected to receive a 204 response from the filter").isEqualTo(204);

        assertThat(receivedRequestWithoutVia.get())
          .as("Expected to receive a request from the client without a Via header")
          .isTrue();
    }

    private HttpProxyServer startProxyServer() {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        return proxyServer;
    }
}
