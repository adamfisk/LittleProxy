package org.littleshoot.proxy.websockets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WebSocketClientServerTest {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(1);
    private static final int MAX_CONNECTION_ATTEMPTS = 3;
    private static final long TEST_TIMEOUT_MILLIS = 10000L;
    private static final Logger logger = LoggerFactory.getLogger(WebSocketClientServerTest.class);
    private HttpProxyServer proxy;
    private WebSocketServer server;
    private WebSocketClient client;

    @Before
    public void setUp() {
        server = new WebSocketServer();
        client = new WebSocketClient();
    }

    @After
    public void tearDown() throws Exception {
        if (client != null) {
            client.close();
            client = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        if (proxy != null) {
            proxy.abort();
            proxy = null;
        }
    }

    private void startProxy(final boolean withSsl) {
        final HttpProxyServerBootstrap bootstrap = DefaultHttpProxyServer.bootstrap().withTransparent(true).withPort(0);
        if (withSsl) {
            bootstrap.withManInTheMiddle(new SelfSignedMitmManager());
        }
        proxy = bootstrap.start();
    }

    @Ignore // Only useful for debugging issues with the proxy tests
    @Test(timeout=TEST_TIMEOUT_MILLIS)
    public void directInsecureConnection() throws Exception {
        testIntegration(false, false);
    }

    @Ignore // Only useful for debugging issues with the proxy tests
    @Test(timeout=TEST_TIMEOUT_MILLIS)
    public void directSecureConnection() throws Exception {
        testIntegration(true, false);
    }

    @Test(timeout=TEST_TIMEOUT_MILLIS)
    public void proxiedInsecureConnectionWsScheme() throws Exception {
        testIntegration(false, true, "ws");
    }
    
    @Test(timeout=TEST_TIMEOUT_MILLIS)
    public void proxiedInsecureConnectionHttpScheme() throws Exception {
        testIntegration(false, true, "http");
    }

    @Test(timeout=TEST_TIMEOUT_MILLIS)
    public void proxiedSecureConnectionWssScheme() throws Exception {
        testIntegration(true, true, "wss");
    }

    @Test(timeout=TEST_TIMEOUT_MILLIS)
    public void proxiedSecureConnectionHttpsScheme() throws Exception {
        testIntegration(true, true, "https");
    }

    private void testIntegration(final boolean withSsl, final boolean withProxy) throws Exception {
        testIntegration(withSsl, withProxy, withSsl ? "wss" : "ws");
    }
    
    private void testIntegration(final boolean withSsl, final boolean withProxy, final String scheme) throws Exception {
        final InetSocketAddress serverAddress = server.start(withSsl);
        if (withProxy) {
            startProxy(withSsl);
        }
        
        final URI serverUri = URI.create(scheme + "://" + serverAddress.getHostString() + ":" + serverAddress.getPort()
                + WebSocketServer.WEBSOCKET_PATH);

        openClient(serverUri, withProxy);

        final String request = "test";
        client.send(request);
        final String response = client.waitForResponse(Duration.ofSeconds(5));
        assertEquals(request.toUpperCase(), response);
    }
    
    private void openClient(final URI uri, final boolean withProxy) throws InterruptedException {
        final Optional<InetSocketAddress> proxyAddress = Optional.ofNullable(proxy).filter(httpProxy -> withProxy).map(HttpProxyServer::getListenAddress);
        int connectionAttempt = 0;
        boolean connected = false;
        while (!connected && connectionAttempt++ < MAX_CONNECTION_ATTEMPTS) {
            try {
                client.open(uri, CONNECT_TIMEOUT, proxyAddress);
                connected = true;
            } catch (TimeoutException e) {
                logger.warn("Connection attempt {} of {} timed out after {}", connectionAttempt, MAX_CONNECTION_ATTEMPTS, CONNECT_TIMEOUT);
                Thread.sleep(CONNECT_TIMEOUT.toMillis() / 2);
            }
        }
        if (!connected) {
            fail("Connection timed out after " + MAX_CONNECTION_ATTEMPTS + " attempts");
        }
    }
}
