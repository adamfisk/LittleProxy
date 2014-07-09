package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Queue;

/**
 * Tests a proxy chained to a downstream proxy with an untrusted SSL cert. When
 * the downstream proxy is unavailable, the downstream proxy should just fall
 * back to a direct connection.
 */
public class ChainedProxyWithFallbackToDirectDueToSSLTest extends
        BadServerAuthenticationTCPChainedProxyTest {
    @Override
    protected boolean isChained() {
        // Set this to false since we don't actually expect anything to go
        // through the chained proxy
        return false;
    }

    @Override
    protected boolean expectBadGatewayForEverything() {
        return false;
    }

    protected ChainedProxyManager chainedProxyManager() {
        return new ChainedProxyManager() {
            @Override
            public void lookupChainedProxies(HttpRequest httpRequest,
                    Queue<ChainedProxy> chainedProxies) {
                // This first one has a bad cert
                chainedProxies.add(newChainedProxy());
                chainedProxies
                        .add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
            }
        };
    }
}
