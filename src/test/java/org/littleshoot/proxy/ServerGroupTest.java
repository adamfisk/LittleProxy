package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ThreadPoolConfiguration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.concurrent.Executors.newFixedThreadPool;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.test.HttpClientUtil.performLocalHttpGet;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public final class ServerGroupTest {
    private ClientAndServer mockServer;
    private int mockServerPort;

    private HttpProxyServer proxyServer;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @AfterEach
    void tearDown() {
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
    public void testSingleWorkerThreadPoolConfiguration() throws ExecutionException, InterruptedException {
        final String firstRequestPath = "/testSingleThreadFirstRequest";
        final String secondRequestPath = "/testSingleThreadSecondRequest";

        // set up two server responses that will execute more or less simultaneously. the first request has a small
        // delay, to reduce the chance that the first request will finish entirely before the second  request is finished
        // (and thus be somewhat more likely to be serviced by the same thread, even if the ThreadPoolConfiguration is
        // not behaving properly).
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath(firstRequestPath),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withBody("first")
                                .withDelay(TimeUnit.MILLISECONDS, 500)
                );

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath(secondRequestPath),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withBody("second")
                );

        // save the names of the threads that execute the filter methods. filter methods are executed by the worker thread
        // handling the request/response, so if there is only one worker thread, the filter methods should be executed
        // by the same thread.
        final AtomicReference<String> firstClientThreadName = new AtomicReference<>();
        final AtomicReference<String> secondClientThreadName = new AtomicReference<>();

        final AtomicReference<String> firstProxyThreadName = new AtomicReference<>();
        final AtomicReference<String> secondProxyThreadName = new AtomicReference<>();

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @NonNull
                    @Override
                    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public io.netty.handler.codec.http.HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                                if (originalRequest.uri().endsWith(firstRequestPath)) {
                                    firstClientThreadName.set(Thread.currentThread().getName());
                                } else if (originalRequest.uri().endsWith(secondRequestPath)) {
                                    secondClientThreadName.set(Thread.currentThread().getName());
                                }

                                return super.clientToProxyRequest(httpObject);
                            }

                            @Override
                            public void serverToProxyResponseReceived() {
                                if (originalRequest.uri().endsWith(firstRequestPath)) {
                                    firstProxyThreadName.set(Thread.currentThread().getName());
                                } else if (originalRequest.uri().endsWith(secondRequestPath)) {
                                    secondProxyThreadName.set(Thread.currentThread().getName());
                                }
                            }
                        };
                    }
                })
                .withThreadPoolConfiguration(new ThreadPoolConfiguration()
                        .withAcceptorThreads(1)
                        .withClientToProxyWorkerThreads(1)
                        .withProxyToServerWorkerThreads(1))
                .start();

        // execute both requests in parallel, to increase the chance of blocking due to the single-threaded ThreadPoolConfiguration

        Runnable firstRequest = () -> {
            HttpResponse response = performLocalHttpGet(mockServerPort, firstRequestPath, proxyServer);
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        };

        Runnable secondRequest = () -> {
            HttpResponse response = performLocalHttpGet(mockServerPort, secondRequestPath, proxyServer);
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
        };

        ExecutorService executor = newFixedThreadPool(2);
        Future<?> firstFuture = executor.submit(firstRequest);
        Future<?> secondFuture = executor.submit(secondRequest);

        firstFuture.get();
        secondFuture.get();

        Thread.sleep(500);

          assertThat(secondClientThreadName.get())
            .as("Expected clientToProxy filter methods to be executed on the same thread for both requests")
            .isEqualTo(firstClientThreadName.get());
          assertThat(secondProxyThreadName.get())
            .as("Expected serverToProxy filter methods to be executed on the same thread for both requests")
            .isEqualTo(firstProxyThreadName.get());
    }

}
