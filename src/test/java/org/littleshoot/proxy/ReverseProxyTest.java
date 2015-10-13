package org.littleshoot.proxy;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Tests just a single reverse proxy running
 */
public class ReverseProxyTest extends AbstractProxyTest {

    @Override
    protected void setUp() throws Exception {
        HostResolver backendResolver = new HostResolver() {
            @Override
            public InetSocketAddress resolve(String host, int port) throws UnknownHostException {
                return new DefaultHostResolver().resolve("127.0.0.1", webServerPort);
            }
        };
        proxyServer = DefaultHttpProxyServer.bootstrap()
            .withPort(0)
            .withReverse(true)
            .withTransparent(true)
            .withServerResolver(backendResolver)
            .start();
    }

    @Test
    public void testReverseProxying() throws Exception {
        ResponseInfo response = httpGetWithApacheClient(webHost, DEFAULT_RESOURCE, true, false);
        assertEquals(200, response.getStatusCode());
    }

}
