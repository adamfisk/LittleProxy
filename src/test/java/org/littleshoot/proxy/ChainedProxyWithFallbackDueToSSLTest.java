package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Queue;

/**
 * Tests a proxy chained to a downstream proxy with an untrusted SSL cert. When
 * the downstream proxy is unavailable, the downstream proxy should just fall
 * back to a direct connection.
 */
public class ChainedProxyWithFallbackDueToSSLTest extends
        BadClientAuthenticationTCPChainedProxyTest {
    @Override
    protected boolean expectBadGatewayForEverything() {
        return false;
    }

    protected ChainedProxyManager chainedProxyManager() {
        return new ChainedProxyManager() {
            @Override
            public void lookupChainedProxies(HttpRequest httpRequest,
                    Queue<ChainedProxy> chainedProxies) {
                chainedProxies.add(newChainedProxy());
                chainedProxies
                        .add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
            }
        };
    }
}
