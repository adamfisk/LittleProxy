package org.littleshoot.proxy;

/**
 * Tests a single proxy that requires username/password authentication.
 */
public class UsernamePasswordAuthenticatingProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = TestUtils.startProxyServerWithCredentials(
                PROXY_SERVER_PORT, getUsername(),
                getPassword());
    }

    @Override
    protected String getUsername() {
        return "user1";
    }

    @Override
    protected String getPassword() {
        return "user2";
    }
}
