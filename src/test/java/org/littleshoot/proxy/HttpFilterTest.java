package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.eclipse.jetty.server.Server;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.HttpClientUtil;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.test.HttpClientUtil.performHttpGet;
import static org.littleshoot.proxy.test.HttpClientUtil.performLocalHttpGet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public final class HttpFilterTest {
    private Server webServer;
    private HttpProxyServer proxyServer;
    private int webServerPort;

    private ClientAndServer mockServer;
    private int mockServerPort;

    @BeforeEach
    void setUp() throws Exception {
        webServer = new Server(0);
        webServer.start();
        webServerPort = TestUtils.findLocalHttpPort(webServer);

        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();

    }

    @AfterEach
    void tearDown() throws Exception {
        try {
            if (webServer != null) {
                webServer.stop();
            }
        } finally {
            try {
                if (proxyServer != null) {
                    proxyServer.abort();
                }
            } finally {
                if (mockServer != null) {
                    mockServer.stop();
                }
            }
        }
    }

    /**
     * Sets up the HttpProxyServer instance for a test. This method initializes the proxyServer and proxyPort method variables, and should
     * be called before making any requests through the proxy server.
     *
     * The proxy cannot be created in @BeforeEach method because the filtersSource must be initialized by each test before the proxy is
     * created.
     *
     * @param filtersSource HTTP filters source
     */
    private void setUpHttpProxyServer(HttpFiltersSource filtersSource) {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();

        final InetSocketAddress isa = new InetSocketAddress("127.0.0.1", proxyServer.getListenAddress().getPort());
        while (true) {
            try (Socket sock = new Socket()) {
                sock.connect(isa);
                break;
            } catch (final IOException e) {
                // Keep trying.
            }

            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                throw new RuntimeException("Interrupted while verifying proxy is connectable");
            }
        }
    }

    @Test
    public void testFiltering() throws Exception {
        final AtomicInteger shouldFilterCalls = new AtomicInteger(0);
        final AtomicInteger filterResponseCalls = new AtomicInteger(0);
        final AtomicInteger fullHttpRequestsReceived = new AtomicInteger(0);
        final AtomicInteger fullHttpResponsesReceived = new AtomicInteger(0);
        final Queue<HttpRequest> associatedRequests =
                new LinkedList<>();

        final AtomicInteger requestCount = new AtomicInteger(0);
        final AtomicLongArray proxyToServerRequestSendingNanos = new AtomicLongArray(new long[] { -1, -1, -1, -1, -1 });
        final AtomicLongArray proxyToServerRequestSentNanos = new AtomicLongArray(new long[] { -1, -1, -1,-1, -1 });
        final AtomicLongArray serverToProxyResponseReceivingNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray serverToProxyResponseReceivedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionQueuedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionStartedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionSucceededNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray proxyToServerResolutionFailedNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionStartedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionSSLHandshakeStartedNanos = new AtomicLongArray(new long[] {-1, -1, -1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionFailedNanos = new AtomicLongArray(new long[] { -1, -1,-1, -1, -1 });
        final AtomicLongArray proxyToServerConnectionSucceededNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });
        final AtomicLongArray serverToProxyResponseTimedOutNanos = new AtomicLongArray(new long[] { -1,-1, -1, -1, -1 });

        final AtomicReference<ChannelHandlerContext> serverCtxReference = new AtomicReference<>();

        final String url1 = "http://localhost:" + webServerPort + "/";
        final String url2 = "http://localhost:" + webServerPort + "/testing";
        final String url3 = "http://localhost:" + webServerPort + "/testing2";
        final String url4 = "http://localhost:" + webServerPort + "/testing3";
        final String url5 = "http://localhost:" + webServerPort + "/testing4";

        final HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                shouldFilterCalls.incrementAndGet();
                associatedRequests.add(originalRequest);

                return new HttpFiltersAdapter(originalRequest) {
                    @Nullable
                    @Override
                    public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                        fullHttpRequestsReceived.incrementAndGet();
                        if (httpObject instanceof HttpRequest) {
                            HttpRequest httpRequest = (HttpRequest) httpObject;
                            if (httpRequest.uri().equals(url2)) {
                                return new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.FORBIDDEN);
                            }
                        }
                        return null;
                    }

                    @Nullable
                    @Override
                    public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpRequest httpRequest = (HttpRequest) httpObject;
                            if ("/testing2".equals(httpRequest.uri())) {
                                return new DefaultFullHttpResponse(
                                        HttpVersion.HTTP_1_1,
                                        HttpResponseStatus.FORBIDDEN);
                            }
                        }
                        return null;
                    }

                    @Override
                    public void proxyToServerRequestSending() {
                        proxyToServerRequestSendingNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerRequestSent() {
                        proxyToServerRequestSentNanos.set(requestCount.get(), now());
                    }

                    @NonNull
                    public HttpObject serverToProxyResponse(@NonNull HttpObject httpObject) {
                        if (originalRequest.uri().contains("testing3")) {
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
                    }

                    @Override
                    public void serverToProxyResponseTimedOut() {
                        serverToProxyResponseTimedOutNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void serverToProxyResponseReceiving() {
                        serverToProxyResponseReceivingNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void serverToProxyResponseReceived() {
                        serverToProxyResponseReceivedNanos.set(requestCount.get(), now());
                    }

                    @NonNull
                    public HttpObject proxyToClientResponse(@NonNull HttpObject httpObject) {
                        if (originalRequest.uri().contains("testing4")) {
                            return new DefaultFullHttpResponse(
                                    HttpVersion.HTTP_1_1,
                                    HttpResponseStatus.FORBIDDEN);
                        }
                        if (httpObject instanceof HttpResponse) {
                            ((HttpResponse) httpObject).headers().set(
                                    "Header-Post", "2");
                        }
                        return httpObject;
                    }

                    @Override
                    public void proxyToServerConnectionQueued() {
                        proxyToServerConnectionQueuedNanos.set(requestCount.get(), now());
                    }

                    @Nullable
                    @Override
                    public InetSocketAddress proxyToServerResolutionStarted(@NonNull String resolvingServerHostAndPort) {
                        proxyToServerResolutionStartedNanos.set(requestCount.get(), now());
                        return super.proxyToServerResolutionStarted(resolvingServerHostAndPort);
                    }

                    @Override
                    public void proxyToServerResolutionFailed(@NonNull String hostAndPort) {
                        proxyToServerResolutionFailedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(
                      @NonNull String serverHostAndPort,
                      @NonNull InetSocketAddress resolvedRemoteAddress
                    ) {
                        proxyToServerResolutionSucceededNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionStarted() {
                        proxyToServerConnectionStartedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionSSLHandshakeStarted() {
                        proxyToServerConnectionSSLHandshakeStartedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionFailed() {
                        proxyToServerConnectionFailedNanos.set(requestCount.get(), now());
                    }

                    @Override
                    public void proxyToServerConnectionSucceeded(@NonNull ChannelHandlerContext ctx) {
                        proxyToServerConnectionSucceededNanos.set(requestCount.get(), now());
                        serverCtxReference.set(ctx);
                    }

                };
            }

            public int getMaximumRequestBufferSizeInBytes() {
                return 1024 * 1024;
            }

            public int getMaximumResponseBufferSizeInBytes() {
                return 1024 * 1024;
            }
        };

        setUpHttpProxyServer(filtersSource);

        org.apache.http.HttpResponse response1 = performHttpGet(url1, proxyServer);
        // sleep for a short amount of time, to allow the filter methods to be invoked
        Thread.sleep(500);
        assertThat(response1.getFirstHeader("Header-Pre").getValue())
          .as("Response should have included the custom header from our pre filter")
          .isEqualTo("1");
        assertThat(response1.getFirstHeader("Header-Post").getValue())
          .as("Response should have included the custom header from our post filter")
          .isEqualTo("2");

        assertThat(associatedRequests).hasSize(1);
        assertThat(shouldFilterCalls.get()).isEqualTo(1);
        assertThat(fullHttpRequestsReceived.get()).isEqualTo(1);
        assertThat(fullHttpResponsesReceived.get()).isEqualTo(1);
        assertThat(filterResponseCalls.get()).isEqualTo(1);

        int i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i)).isLessThan(proxyToServerResolutionStartedNanos.get(i));
        assertThat(proxyToServerResolutionStartedNanos.get(i)).isLessThan(proxyToServerResolutionSucceededNanos.get(i));
        assertThat(proxyToServerResolutionSucceededNanos.get(i)).isLessThan(proxyToServerConnectionStartedNanos.get(i));
        assertThat(proxyToServerConnectionSSLHandshakeStartedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionFailedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerResolutionFailedNanos.get(i)).isEqualTo(-1);
        assertThat(serverToProxyResponseTimedOutNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionStartedNanos.get(i)).isLessThan(proxyToServerConnectionSucceededNanos.get(i));
        assertThat(proxyToServerConnectionSucceededNanos.get(i)).isLessThan(proxyToServerRequestSendingNanos.get(i));
        assertThat(proxyToServerRequestSendingNanos.get(i)).isLessThan(proxyToServerRequestSentNanos.get(i));
        assertThat(proxyToServerRequestSentNanos.get(i)).isLessThan(serverToProxyResponseReceivingNanos.get(i));
        assertThat(serverToProxyResponseReceivingNanos.get(i)).isLessThan(serverToProxyResponseReceivedNanos.get(i));

        // We just open a second connection here since reusing the original
        // connection is inconsistent.
        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response2 = performHttpGet(url2, proxyServer);
        Thread.sleep(500);

        assertThat(response2.getStatusLine().getStatusCode()).isEqualTo(403);

        assertThat(associatedRequests).hasSize(2);
        assertThat(shouldFilterCalls.get()).isEqualTo(2);
        assertThat(fullHttpRequestsReceived.get()).isEqualTo(2);
        assertThat(fullHttpResponsesReceived.get()).isEqualTo(1);
        assertThat(filterResponseCalls.get()).isEqualTo(1);

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response3 = performHttpGet(url3, proxyServer);
        Thread.sleep(500);

        assertThat(response3.getStatusLine().getStatusCode()).isEqualTo(403);

        assertThat(associatedRequests).hasSize(3);
        assertThat(shouldFilterCalls.get()).isEqualTo(3);
        assertThat(fullHttpRequestsReceived.get()).isEqualTo(3);
        assertThat(fullHttpResponsesReceived.get()).isEqualTo(1);
        assertThat(filterResponseCalls.get()).isEqualTo(1);

        i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i)).isLessThan(proxyToServerResolutionStartedNanos.get(i));
        assertThat(proxyToServerResolutionStartedNanos.get(i)).isLessThan(proxyToServerResolutionSucceededNanos.get(i));
        assertThat(proxyToServerConnectionStartedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionSSLHandshakeStartedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionFailedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionSucceededNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerRequestSendingNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerRequestSentNanos.get(i)).isEqualTo(-1);
        assertThat(serverToProxyResponseReceivingNanos.get(i)).isEqualTo(-1);
        assertThat(serverToProxyResponseReceivedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerResolutionFailedNanos.get(i)).isEqualTo(-1);
        assertThat(serverToProxyResponseTimedOutNanos.get(i)).isEqualTo(-1);

        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        final HttpRequest third = associatedRequests.remove();

        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertThat(first.uri()).isEqualTo(url1);
        assertThat(second.uri()).isEqualTo(url2);
        assertThat(third.uri()).isEqualTo(url3);

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response4 = performHttpGet(url4, proxyServer);
        Thread.sleep(500);

        i = requestCount.get();
        assertThat(proxyToServerConnectionQueuedNanos.get(i)).isLessThan(proxyToServerResolutionStartedNanos.get(i));
        assertThat(proxyToServerResolutionStartedNanos.get(i)).isLessThan(proxyToServerResolutionSucceededNanos.get(i));
        assertThat(proxyToServerResolutionSucceededNanos.get(i)).isLessThan(proxyToServerConnectionStartedNanos.get(i));
        assertThat(proxyToServerConnectionSSLHandshakeStartedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionFailedNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerResolutionFailedNanos.get(i)).isEqualTo(-1);
        assertThat(serverToProxyResponseTimedOutNanos.get(i)).isEqualTo(-1);
        assertThat(proxyToServerConnectionStartedNanos.get(i)).isLessThan(proxyToServerConnectionSucceededNanos.get(i));
        assertThat(proxyToServerConnectionSucceededNanos.get(i)).isLessThan(proxyToServerRequestSendingNanos.get(i));
        assertThat(proxyToServerRequestSendingNanos.get(i)).isLessThan(proxyToServerRequestSentNanos.get(i));
        assertThat(proxyToServerRequestSentNanos.get(i)).isLessThan(serverToProxyResponseReceivingNanos.get(i));
        assertThat(serverToProxyResponseReceivingNanos.get(i)).isLessThan(serverToProxyResponseReceivedNanos.get(i));

        requestCount.incrementAndGet();
        org.apache.http.HttpResponse response5 = performHttpGet(url5, proxyServer);

        assertThat(response4.getStatusLine().getStatusCode()).isEqualTo(403);
        assertThat(response5.getStatusLine().getStatusCode()).isEqualTo(403);

        assertThat(serverCtxReference.get())
          .as("Server channel context from proxyToServerConnectionSucceeded() should not be null")
          .isNotNull();

        InetSocketAddress remoteAddress = (InetSocketAddress) serverCtxReference.get().channel().remoteAddress();
        assertThat(remoteAddress)
          .as("Server's remoteAddress from proxyToServerConnectionSucceeded() should not be null")
          .isNotNull();
        // make sure we're getting the right remote address (and therefore the right server channel context) in the
        // proxyToServerConnectionSucceeded() filter method
        assertThat(remoteAddress.getHostName()).as("Server's remoteAddress should connect to localhost").isEqualTo("localhost");
        assertThat(remoteAddress.getPort()).as("Server's port should match the web server port").isEqualTo(webServerPort);

        webServer.stop();
    }

    @Test
    public void testResolutionStartedFilterReturnsUnresolvedAddress() throws Exception {
        final AtomicBoolean resolutionSucceeded = new AtomicBoolean(false);

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public InetSocketAddress proxyToServerResolutionStarted(@NonNull String resolvingServerHostAndPort) {
                        return InetSocketAddress.createUnresolved("localhost", webServerPort);
                    }

                    @Override
                    public void proxyToServerResolutionSucceeded(@NonNull String serverHostAndPort, @NonNull InetSocketAddress resolvedRemoteAddress) {
                        assertThat(resolvedRemoteAddress.isUnresolved()).as("expected to receive a resolved InetSocketAddress").isFalse();
                        resolutionSucceeded.set(true);
                    }
                };
            }
        };

        setUpHttpProxyServer(filtersSource);

        performLocalHttpGet(webServerPort, "/", proxyServer);
        Thread.sleep(500);

        assertThat(resolutionSucceeded.get()).as("proxyToServerResolutionSucceeded method was not called").isTrue();
    }

    @Test
    public void testResolutionFailedCalledAfterDnsFailure() throws Exception {
        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return filter;
            }
        };

        HostResolver mockFailingResolver = mock();
        when(mockFailingResolver.resolve("www.does-not-exist", 80)).thenThrow(new UnknownHostException("www.does-not-exist"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withServerResolver(mockFailingResolver)
                .start();

        performHttpGet("http://www.doesnotexist/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertThat(filter.isProxyToServerResolutionSucceededInvoked()).as("proxyToServerResolutionSucceeded method was called but should not have been").isFalse();
        assertThat(filter.isProxyToServerResolutionFailedInvoked()).as("proxyToServerResolutionFailed method was not called").isTrue();

        assertThat(filter.isClientToProxyRequestInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionQueuedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerResolutionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToClientResponseInvoked()).as("Expected filter method to be called").isTrue();

        assertThat(filter.isProxyToServerConnectionStartedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerAllowMitmInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionFailedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionSucceededInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSendingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSentInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionSSLHandshakeStartedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseTimedOutInvoked()).as("Expected filter method to not be called").isFalse();
    }

    @Test
    public void testConnectionFailedCalledAfterConnectionFailure() throws Exception {
        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return filter;
            }
        };

        setUpHttpProxyServer(filtersSource);

        // port 0 is not connectable
        performHttpGet("http://localhost:0/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertThat(filter.isProxyToServerConnectionSucceededInvoked()).as("proxyToServerConnectionSucceeded should not be called when connection fails").isFalse();
        assertThat(filter.isProxyToServerConnectionFailedInvoked()).as("proxyToServerConnectionFailed should be called when connection fails").isTrue();

        assertThat(filter.isClientToProxyRequestInvoked()).as("Expected filter method to be called").isTrue();
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertThat(filter.isProxyToServerRequestInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionQueuedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerResolutionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerResolutionSucceededInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToClientResponseInvoked()).as("Expected filter method to be called").isTrue();

        assertThat(filter.isProxyToServerAllowMitmInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSendingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSentInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionSSLHandshakeStartedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionFailedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseTimedOutInvoked()).as("Expected filter method to not be called").isFalse();
    }

    /**
     * Verifies the proper filters are invoked when an attempt to connect to an unencrypted upstream chained proxy fails.
     */
    @Test
    public void testFiltersAfterUnencryptedConnectionToUpstreamProxyFails() throws Exception {
        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return filter;
            }
        };

        // set up the proxy that the HTTP client will connect to
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withChainProxyManager((httpRequest, chainedProxies, clientDetails) -> chainedProxies.add(new ChainedProxyAdapter() {
                    @Override
                    public InetSocketAddress getChainedProxyAddress() {
                        // port 0 is unconnectable
                        return new InetSocketAddress("127.0.0.1", 0);
                    }
                }))
                .start();

        // the server doesn't have to exist, since the connection to the chained proxy will fail
        performHttpGet("http://localhost:1234/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertThat(filter.isProxyToServerConnectionSucceededInvoked()).as("proxyToServerConnectionSucceeded should not be called when connection to chained proxy fails").isFalse();
        assertThat(filter.isProxyToServerConnectionFailedInvoked()).as("proxyToServerConnectionFailed should be called when connection to chained proxy fails").isTrue();

        assertThat(filter.isClientToProxyRequestInvoked()).as("Expected filter method to be called").isTrue();
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertThat(filter.isProxyToServerRequestInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionQueuedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToClientResponseInvoked()).as("Expected filter method to be called").isTrue();

        assertThat(filter.isProxyToServerAllowMitmInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionSSLHandshakeStartedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionStartedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionSucceededInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSendingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSentInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionFailedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseTimedOutInvoked()).as("Expected filter method to not be called").isFalse();
    }

    /**
     * Verifies the proper filters are invoked when an attempt to connect to an upstream chained proxy over SSL fails.
     * (The proxyToServerConnectionFailed() filter method is particularly important.)
     */
    @Test
    public void testFiltersAfterSSLConnectionToUpstreamProxyFails() throws Exception {
        // create an upstream chained proxy using the same SSL engine as the chained proxy tests
        final HttpProxyServer chainedProxy = DefaultHttpProxyServer.bootstrap()
                .withName("ChainedProxy")
                .withPort(0)
                .withSslEngineSource(new SelfSignedSslEngineSource("target/chain_proxy_keystore_1.jks"))
                .start();

        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return filter;
            }
        };

        // set up the proxy that the HTTP client will connect to
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withChainProxyManager((httpRequest, chainedProxies, clientDetails) -> chainedProxies.add(new ChainedProxyAdapter() {
                    @Override
                    public InetSocketAddress getChainedProxyAddress() {
                        return chainedProxy.getListenAddress();
                    }

                    @Override
                    public boolean requiresEncryption() {
                        return true;
                    }

                    @Override
                    public SSLEngine newSslEngine() {
                        // use the same "bad" keystore as BadServerAuthenticationTCPChainedProxyTest
                        return new SelfSignedSslEngineSource("target/chain_proxy_keystore_2.jks").newSslEngine();
                    }
                }))
                .start();

        // the server doesn't have to exist, since the connection to the chained proxy will fail
        performHttpGet("http://localhost:1234/some-resource", proxyServer);
        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertThat(filter.isProxyToServerConnectionSucceededInvoked()).as("proxyToServerConnectionSucceeded should not be called when connection to chained proxy fails").isFalse();
        assertThat(filter.isProxyToServerConnectionFailedInvoked()).as("proxyToServerConnectionFailed should be called when connection to chained proxy fails").isTrue();

        assertThat(filter.isClientToProxyRequestInvoked()).as("Expected filter method to be called").isTrue();
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertThat(filter.isProxyToServerRequestInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionQueuedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToClientResponseInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionSSLHandshakeStartedInvoked()).as("Expected filter method to be called").isTrue();

        assertThat(filter.isProxyToServerAllowMitmInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionStartedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionSucceededInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSendingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerRequestSentInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionFailedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseTimedOutInvoked()).as("Expected filter method to not be called").isFalse();
    }

    @Test
    public void testResponseTimedOutInvokedAfterServerTimeout() throws Exception {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/servertimeout"),
                Times.once())
                .respond(response()
                        .withStatusCode(200)
                        .withDelay(TimeUnit.SECONDS, 10)
                        .withBody("success"));

        final HttpFiltersMethodInvokedAdapter filter = new HttpFiltersMethodInvokedAdapter();

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return filter;
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .withIdleConnectionTimeout(3)
                .start();

        org.apache.http.HttpResponse httpResponse = performLocalHttpGet(mockServerPort, "/servertimeout", proxyServer);
        assertThat(httpResponse.getStatusLine().getStatusCode())
          .as("Expected to receive an HTTP 504 Gateway Timeout from proxy")
          .isEqualTo(504);

        Thread.sleep(500);

        // verify that the filters related to this functionality were correctly invoked/not invoked as appropriate, but also verify that
        // other filters were invoked/not invoked as expected
        assertThat(filter.isServerToProxyResponseTimedOutInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isServerToProxyResponseReceivingInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isServerToProxyResponseReceivedInvoked()).as("Expected filter method to not be called").isFalse();

        assertThat(filter.isClientToProxyRequestInvoked()).as("Expected filter method to be called").isTrue();
        // proxyToServerRequest is invoked before the connection is made, so it should be hit
        assertThat(filter.isProxyToServerRequestInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionQueuedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerResolutionStartedInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerResolutionSucceededInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerRequestSendingInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerRequestSentInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToServerConnectionSucceededInvoked()).as("Expected filter method to be called").isTrue();
        assertThat(filter.isProxyToClientResponseInvoked()).as("Expected filter method to be called").isTrue();

        assertThat(filter.isProxyToServerAllowMitmInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerResolutionFailedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionFailedInvoked()).as("Expected filter method to not be called").isFalse();
        assertThat(filter.isProxyToServerConnectionSSLHandshakeStartedInvoked()).as("Expected filter method to not be called").isFalse();
    }

    @Test
    public void testRequestSentInvokedAfterLastHttpContentSent() throws Exception {
        final AtomicBoolean lastHttpContentProcessed = new AtomicBoolean(false);
        final AtomicBoolean requestSentCallbackInvoked = new AtomicBoolean(false);

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Nullable
                    @Override
                    public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
                        if (httpObject instanceof LastHttpContent) {
                            assertThat(requestSentCallbackInvoked.get()).as("requestSentCallback should not be invoked until the LastHttpContent is processed").isFalse();

                            lastHttpContentProcessed.set(true);
                        }

                        return null;
                    }

                    @Override
                    public void proxyToServerRequestSent() {
                        // proxyToServerRequestSent should only be invoked after the entire request, including payload, has been sent to the server
                        assertThat(lastHttpContentProcessed.get()).as("proxyToServerRequestSent callback invoked before LastHttpContent was received from the client and sent to the server").isTrue();

                        requestSentCallbackInvoked.set(true);
                    }
                };
            }
        };

        setUpHttpProxyServer(filtersSource);

        // test with a POST request with a payload. post a large amount of data, to force chunked content.
        HttpClientUtil.performHttpPost("http://localhost:" + webServerPort + "/", 50000, proxyServer);
        Thread.sleep(500);

        assertThat(lastHttpContentProcessed.get()).as("proxyToServerRequest callback was not invoked for LastHttpContent for chunked POST").isTrue();
        assertThat(requestSentCallbackInvoked.get()).as("proxyToServerRequestSent callback was not invoked for chunked POST").isTrue();

        // test with a non-payload-bearing GET request.
        lastHttpContentProcessed.set(false);
        requestSentCallbackInvoked.set(false);

        performLocalHttpGet(webServerPort, "/", proxyServer);
        Thread.sleep(500);

        assertThat(lastHttpContentProcessed.get()).as("proxyToServerRequest callback was not invoked for LastHttpContent for GET").isTrue();
        assertThat(requestSentCallbackInvoked.get()).as("proxyToServerRequestSent callback was not invoked for GET").isTrue();
    }

    /**
     * Verifies that the proxy properly handles a null HttpFilters instance, as allowed in the
     * {@link HttpFiltersSource#filterRequest(HttpRequest, ChannelHandlerContext)} documentation.
     */
    @Test
    public void testNullHttpFilterSource() throws Exception {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/testNullHttpFilterSource"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Nullable
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return null;
            }
        };

        setUpHttpProxyServer(filtersSource);

        org.apache.http.HttpResponse httpResponse = performLocalHttpGet(mockServerPort, "/testNullHttpFilterSource", proxyServer);
        Thread.sleep(500);

        assertThat(httpResponse.getStatusLine().getStatusCode())
          .as("Expected to receive an HTTP 200 from proxy")
          .isEqualTo(200);
    }

    private long now() {
        // using nanoseconds instead of milliseconds, since it is extremely unlikely that any two callbacks would be invoked in the same nanosecond,
        // even on very fast hardware
        return System.nanoTime();
    }

    /**
     * HttpFilters instance that monitors HttpFilters methods and tracks which methods have been invoked.
     */
    private static class HttpFiltersMethodInvokedAdapter implements HttpFilters {
        private final AtomicBoolean proxyToServerConnectionFailed = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionSucceeded = new AtomicBoolean(false);
        private final AtomicBoolean clientToProxyRequest = new AtomicBoolean(false);
	private final AtomicBoolean proxyToServerAllowMitm = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerRequest = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerRequestSending = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerRequestSent = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponse = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponseReceiving = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponseReceived = new AtomicBoolean(false);
        private final AtomicBoolean proxyToClientResponse = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionStarted = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionQueued = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerResolutionStarted = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerResolutionFailed = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerResolutionSucceeded = new AtomicBoolean(false);
        private final AtomicBoolean proxyToServerConnectionSSLHandshakeStarted = new AtomicBoolean(false);
        private final AtomicBoolean serverToProxyResponseTimedOut = new AtomicBoolean(false);

        public boolean isProxyToServerConnectionFailedInvoked() {
            return proxyToServerConnectionFailed.get();
        }

        public boolean isProxyToServerConnectionSucceededInvoked() {
            return proxyToServerConnectionSucceeded.get();
        }

        public boolean isClientToProxyRequestInvoked() {
            return clientToProxyRequest.get();
        }

	public boolean isProxyToServerAllowMitmInvoked() {
            return proxyToServerAllowMitm.get();
	}

        public boolean isProxyToServerRequestInvoked() {
            return proxyToServerRequest.get();
        }

        public boolean isProxyToServerRequestSendingInvoked() {
            return proxyToServerRequestSending.get();
        }

        public boolean isProxyToServerRequestSentInvoked() {
            return proxyToServerRequestSent.get();
        }

        public boolean isServerToProxyResponseInvoked() {
            return serverToProxyResponse.get();
        }

        public boolean isServerToProxyResponseReceivingInvoked() {
            return serverToProxyResponseReceiving.get();
        }

        public boolean isServerToProxyResponseReceivedInvoked() {
            return serverToProxyResponseReceived.get();
        }

        public boolean isProxyToClientResponseInvoked() {
            return proxyToClientResponse.get();
        }

        public boolean isProxyToServerConnectionStartedInvoked() {
            return proxyToServerConnectionStarted.get();
        }

        public boolean isProxyToServerConnectionQueuedInvoked() {
            return proxyToServerConnectionQueued.get();
        }

        public boolean isProxyToServerResolutionStartedInvoked() {
            return proxyToServerResolutionStarted.get();
        }

        public boolean isProxyToServerResolutionFailedInvoked() {
            return proxyToServerResolutionFailed.get();
        }

        public boolean isProxyToServerResolutionSucceededInvoked() {
            return proxyToServerResolutionSucceeded.get();
        }

        public boolean isProxyToServerConnectionSSLHandshakeStartedInvoked() {
            return proxyToServerConnectionSSLHandshakeStarted.get();
        }

        public boolean isServerToProxyResponseTimedOutInvoked() {
            return serverToProxyResponseTimedOut.get();
        }

        @Override
        public void proxyToServerConnectionFailed() {
            proxyToServerConnectionFailed.set(true);
        }

        @Override
        public void proxyToServerConnectionSucceeded(@NonNull ChannelHandlerContext serverCtx) {
            proxyToServerConnectionSucceeded.set(true);
        }

        @Nullable
        @Override
        public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
            clientToProxyRequest.set(true);
            return null;
        }

        @Nullable
        @Override
        public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
            proxyToServerRequest.set(true);
            return null;
        }

        @Override
        public void proxyToServerRequestSending() {
            proxyToServerRequestSending.set(true);
        }

        @Override
        public void proxyToServerRequestSent() {
            proxyToServerRequestSent.set(true);
        }

        @Nullable
        @Override
        public HttpObject serverToProxyResponse(@NonNull HttpObject httpObject) {
            serverToProxyResponse.set(true);
            return httpObject;
        }

        @Override
        public void serverToProxyResponseTimedOut() {
            serverToProxyResponseTimedOut.set(true);
        }

        @Override
        public void serverToProxyResponseReceiving() {
            serverToProxyResponseReceiving.set(true);
        }

        @Override
        public void serverToProxyResponseReceived() {
            serverToProxyResponseReceived.set(true);
        }

        @Override
        public HttpObject proxyToClientResponse(@NonNull HttpObject httpObject) {
            proxyToClientResponse.set(true);
            return httpObject;
        }

        @Override
        public void proxyToServerConnectionQueued() {
            proxyToServerConnectionQueued.set(true);
        }

        @Override
        public InetSocketAddress proxyToServerResolutionStarted(@NonNull String resolvingServerHostAndPort) {
            proxyToServerResolutionStarted.set(true);
            return null;
        }

        @Override
        public void proxyToServerResolutionFailed(@NonNull String hostAndPort) {
            proxyToServerResolutionFailed.set(true);
        }

        @Override
        public void proxyToServerResolutionSucceeded(@NonNull String serverHostAndPort, @NonNull InetSocketAddress resolvedRemoteAddress) {
            proxyToServerResolutionSucceeded.set(true);
        }

        @Override
        public void proxyToServerConnectionStarted() {
            proxyToServerConnectionStarted.set(true);
        }

        @Override
        public void proxyToServerConnectionSSLHandshakeStarted() {
            proxyToServerConnectionSSLHandshakeStarted.set(true);
        }

       @Override
       public boolean proxyToServerAllowMitm() {
            clientToProxyRequest.set(true);
	    return true;
       }
    }
}
