package org.littleshoot.proxy;

import org.littleshoot.proxy.extras.SelfSignedMitmManager;

/**
 * Tests just a single basic proxy.
 */
public class MitmProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(proxyServerPort)
                .withSslManInTheMiddle(new SelfSignedMitmManager())
                .start();
    }
    
    @Override
    protected boolean isMITM() {
        return true;
    }
}
