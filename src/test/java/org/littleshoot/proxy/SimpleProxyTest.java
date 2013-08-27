package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Tests just a single basic proxy.
 */
public class SimpleProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = DefaultHttpProxyServer.configure()
                .withPort(PROXY_SERVER_PORT)
                .start();
    }
}
