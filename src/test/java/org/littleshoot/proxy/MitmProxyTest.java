package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Queue;

import org.littleshoot.proxy.extras.SelfSignedMitmManager;

/**
 * Tests just a single basic proxy.
 */
public class MitmProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(proxyServerPort)
                // Include a ChainedProxyManager to make sure that MITM setting
                // overrides this
                .withChainProxyManager(new ChainedProxyManager() {
                    @Override
                    public void lookupChainedProxies(HttpRequest httpRequest,
                            Queue<ChainedProxy> chainedProxies) {
                    }
                })
                .withManInTheMiddle(new SelfSignedMitmManager())
                .start();
    }

    @Override
    protected boolean isMITM() {
        return true;
    }
}
