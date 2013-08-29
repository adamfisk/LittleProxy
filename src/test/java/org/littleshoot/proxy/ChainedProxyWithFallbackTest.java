package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Tests a proxy chained to a missing downstream proxy. When the downstream
 * proxy is unavailable, the upstream proxy should just fall back to a direct
 * connection.
 */
public class ChainedProxyWithFallbackTest extends BaseProxyTest {
    private static final int DOWNSTREAM_PROXY_PORT = 49999;
    private static final String DOWNSTREAM_PROXY_HOST_AND_PORT = "127.0.0.1:"
            + DOWNSTREAM_PROXY_PORT;

    @Override
    protected void setUp() {
        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(proxyServerPort)
                .withChainProxyManager(new ChainedProxyManagerAdapter() {
                    @Override
                    public String getHostAndPort(HttpRequest httpRequest) {
                        return DOWNSTREAM_PROXY_HOST_AND_PORT;
                    }

                    @Override
                    public boolean allowFallbackToUnchainedConnection(
                            HttpRequest httpRequest) {
                        return true;
                    }
                })
                .start();
    }
}
