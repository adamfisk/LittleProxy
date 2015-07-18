package org.littleshoot.proxy;

import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThat;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.net.Socket;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.SocketClientUtil;

/**
 * This class tests direct requests to the proxy server, which causes endless
 * loops (#205).
 */
public class DirectRequestTest {
    private HttpProxyServer proxyServer;

    private Socket socket;

    @Before
    public void setUp() throws Exception {
        socket = null;
        proxyServer = null;
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        } finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    @Test(timeout = 5000)
    public void testDirectRequestAnsweredBadRequest() throws Exception {

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest);
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()//
                .withPort(0)//
                .withFiltersSource(filtersSource)//
                .start();

        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        int proxyPort = proxyServer.getListenAddress().getPort();
        String successfulGet = "GET http://localhost:" + proxyPort + "/directToProxy HTTP/1.1\n" + "\r\n";
        SocketClientUtil.writeStringToSocket(successfulGet, socket);

        Thread.sleep(750);

        String response = SocketClientUtil.readStringFromSocket(socket);

        assertThat("Expected to receive an HTTP 400 from the server", response, startsWith("HTTP/1.1 400 "));
    }

    @Test(timeout = 5000)
    public void testDirectRequestAnswerUnmodified() throws Exception {

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(403));
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()//
                .withPort(0)//
                .withFiltersSource(filtersSource)//
                .start();

        socket = SocketClientUtil.getSocketToProxyServer(proxyServer);

        int proxyPort = proxyServer.getListenAddress().getPort();
        String successfulGet = "GET http://localhost:" + proxyPort + "/directToProxy HTTP/1.1\n" + "\r\n";
        SocketClientUtil.writeStringToSocket(successfulGet, socket);

        Thread.sleep(750);

        String response = SocketClientUtil.readStringFromSocket(socket);

        assertThat("Expected to receive an HTTP 403 from the server", response, startsWith("HTTP/1.1 403 "));
    }
}
