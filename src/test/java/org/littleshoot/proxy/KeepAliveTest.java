package org.littleshoot.proxy;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.SocketClientUtil;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ConnectionOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.Socket;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * This class tests the proxy's keep alive/connection closure behavior.
 */
@Tag("slow-test")
@NullMarked
public final class KeepAliveTest {
    private static final Logger log = LoggerFactory.getLogger(KeepAliveTest.class);
    @Nullable
    private HttpProxyServer proxyServer;
    private ClientAndServer mockServer;
    private int mockServerPort;
    @Nullable
    private Socket socket;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
        log.info("Mock server port: {} (started: {})", mockServerPort, mockServer.hasStarted());
    }

    @AfterEach
    @SuppressWarnings("ConstantValue")
    void tearDown() throws Exception {
        try {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        } finally {
            try {
                if (mockServer != null) {
                    mockServer.stop();
                }
            } finally {
                if (socket != null) {
                    socket.close();
                }
            }
        }
    }

    /**
     * Tests that the proxy does not close the connection after a successful HTTP 1.1 GET request and response.
     */
    @Test
    public void testHttp11DoesNotCloseConnectionByDefault() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"), Times.exactly(2))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        // construct the basic request: METHOD + URI + HTTP version + CRLF (to indicate the end of the request)
        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
            assertThat(response)
              .as("Unexpected message body (iteration: %s)", i)
              .endsWith("success");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy keeps the connection to the client open after a server disconnect, even when the server is using
     * connection closure to indicate the end of a message.
     */
    @Test
    public void testProxyKeepsClientConnectionOpenAfterServerDisconnect() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"), Times.exactly(2))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success")
                        .withConnectionOptions(new ConnectionOptions()
                                .withKeepAliveOverride(false)
                                .withSuppressContentLengthHeader(true)
                                .withCloseSocket(true)));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        // construct the basic request: METHOD + URI + HTTP version + CRLF (to indicate the end of the request)
        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
            // the proxy will set the Transfer-Encoding to chunked since the server is using connection closure to indicate the end of the message.
            // (matching capitalized or lowercase Transfer-Encoding, since Netty 4.1+ uses lowercase header names)
            assertThat(response.toLowerCase(Locale.US))
              .as("Expected proxy to set Transfer-Encoding to chunked")
              .contains("transfer-encoding: chunked");
            // the Transfer-Encoding is chunked, so the body text will be followed by a 0 and 2 CRLFs
            assertThat(response)
              .as("Unexpected message body (iteration: %s)", i)
              .contains("success");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy does not close the connection after a 502 Bad Gateway response.
     */
    @Test
    public void testBadGatewayDoesNotCloseConnection() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"), Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String badGatewayGet = "GET http://localhost:0/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), badGatewayGet);
            SocketClientUtil.writeStringToSocket(badGatewayGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(1500);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 502 Bad Gateway");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy does not close the connection after a 504 Gateway Timeout response.
     */
    @Test
    public void testGatewayTimeoutDoesNotCloseConnection() throws IOException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"), Times.exactly(2))
                .respond(response()
                        .withStatusCode(200)
                        .withDelay(TimeUnit.SECONDS, 10)
                        .withBody("success"));

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withIdleConnectionTimeout(Duration.ofSeconds(2))
                .withPort(0)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

	          // match the whole response to make sure that it's not repeated
            assertThat(response).as("The response is repeated:").isEqualTo("HTTP/1.1 504 Gateway Timeout\r\n" +
                    "content-length: 15\r\n" +
                    "content-type: text/html; charset=utf-8\r\n" +
                    "\r\n" +
                    "Gateway Timeout");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy does not close the connection by default after a short-circuit response.
     */
    @Test
    public void testShortCircuitResponseDoesNotCloseConnectionByDefault() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"), Times.exactly(1))
                .respond(response()
                        .withStatusCode(500)
                        .withBody("this response should never be sent"));

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Nullable
                    @Override
                    public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpResponse shortCircuitResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                            HttpUtil.setContentLength(shortCircuitResponse, 0);
                            return shortCircuitResponse;
                        } else {
                            return null;
                        }
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // send the same request twice over the same connection
        for (int i = 1; i <= 2; i++) {
            log.debug("#{} Sending to socket {} packet '{}'...", i, socket.getLocalPort(), successfulGet);
            SocketClientUtil.writeStringToSocket(successfulGet, socket);

            // wait a bit to allow the proxy server to respond
            Thread.sleep(750);

            log.debug("#{} Reading from socket {}...", i, socket.getLocalPort());
            String response = SocketClientUtil.readStringFromSocket(socket);

            assertThat(response)
              .as("Expected to receive an HTTP 200 from the server (iteration: %s)", i)
              .startsWith("HTTP/1.1 200 OK");
        }

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be open and readable")
          .isTrue();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be open and writable")
          .isTrue();
    }

    /**
     * Tests that the proxy will close the connection after a short circuit response if the short circuit response
     * contains a Connection: close header.
     */
    @Test
    public void testShortCircuitResponseCanCloseConnection() throws IOException, InterruptedException {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(500)
                        .withBody("this response should never be sent"));

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @NonNull
            @Override
            public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Nullable
                    @Override
                    public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpResponse shortCircuitResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                            HttpUtil.setContentLength(shortCircuitResponse, 0);
                            HttpUtil.setKeepAlive(shortCircuitResponse, false);
                            return shortCircuitResponse;
                        } else {
                            return null;
                        }
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(filtersSource)
                .start();
        log.info("Started proxy server {}", proxyServer.getListenAddress());
        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        String successfulGet = "GET http://localhost:" + mockServerPort + "/success HTTP/1.1\r\n"
                + "\r\n";

        // only send this request once, since we expect the short circuit response to close the connection
        log.debug("Sending to socket {} packet '{}'...", socket.getLocalPort(), successfulGet);
        SocketClientUtil.writeStringToSocket(successfulGet, socket);

        // wait a bit to allow the proxy server to respond
        Thread.sleep(750);

        log.debug("Reading from socket {}...", socket.getLocalPort());
        String response = SocketClientUtil.readStringFromSocket(socket);

        assertThat(response)
          .as("Expected to receive an HTTP 200 from the server")
          .startsWith("HTTP/1.1 200 OK");

        assertThat(SocketClientUtil.isSocketReadyToRead(socket))
          .as("Expected connection to proxy server to be closed")
          .isFalse();
        assertThat(SocketClientUtil.isSocketReadyToWrite(socket))
          .as("Expected connection to proxy server to be closed")
          .isFalse();
    }
}

