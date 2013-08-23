package org.littleshoot.proxy;

/**
 * Tests just a single basic proxy.
 */
public class SimpleProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = TestUtils.startProxyServer(PROXY_SERVER_PORT);
    }
}
