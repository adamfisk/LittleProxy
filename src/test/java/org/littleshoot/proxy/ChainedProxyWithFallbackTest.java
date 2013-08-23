package org.littleshoot.proxy;

/**
 * Tests a proxy chained to a missing downstream proxy. When the downstream
 * proxy is unavailable, the upstream proxy should just fall back to a direct
 * connection.
 */
public class ChainedProxyWithFallbackTest extends BaseProxyTest {
    private static final int DOWNSTREAM_PROXY_PORT = PROXY_SERVER_PORT + 1;
    private static final String DOWNSTREAM_PROXY_HOST_AND_PORT = "127.0.0.1:"
            + DOWNSTREAM_PROXY_PORT;

    @Override
    protected void setUp() {
        this.proxyServer = TestUtils.startProxyServer(PROXY_SERVER_PORT,
                DOWNSTREAM_PROXY_HOST_AND_PORT);
    }
}
