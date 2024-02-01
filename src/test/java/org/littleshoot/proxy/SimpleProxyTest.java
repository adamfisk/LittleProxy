package org.littleshoot.proxy;

/**
 * Tests just a single basic proxy.
 */
public class SimpleProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
                .withPort(0)
                .start();
    }
}
