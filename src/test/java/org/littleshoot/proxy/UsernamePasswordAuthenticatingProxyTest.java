package org.littleshoot.proxy;

/**
 * Tests a single proxy that requires username/password authentication.
 */
public class UsernamePasswordAuthenticatingProxyTest extends BaseProxyTest
        implements ProxyAuthenticator {
    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(0)
                .withProxyAuthenticator(this)
                .start();
    }

    @Override
    protected String getUsername() {
        return "user1";
    }

    @Override
    protected String getPassword() {
        return "user2";
    }

    @Override
    public boolean authenticate(String proxyAuthorizationHeaderValue) {
        return new TestBasicProxyAuthenticator(getUsername(), getPassword()).authenticate(proxyAuthorizationHeaderValue);
    }

    @Override
    protected boolean isAuthenticating() {
        return true;
    }

    @Override
    public String getRealm() {
        return null;
    }

    static class TestBasicProxyAuthenticator extends BasicProxyAuthenticator{

        private final String username;
        private final String password;

        TestBasicProxyAuthenticator(String username, String password) {
            this.username = username;
            this.password = password;
        }

        @Override
        boolean authenticate(String username, String password) {
            return this.username.equals(username) && this.password.equals(password);
        }

        @Override
        public String getRealm() {
            return null;
        }
    }
}
