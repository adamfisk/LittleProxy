package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Tests a single proxy that requires username/password authentication.
 */
public class UsernamePasswordAuthenticatingProxyTest extends BaseProxyTest
        implements ProxyAuthenticator {
    @Override
    protected void setUp() {
        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(proxyServerPort)
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
    public boolean authenticate(String userName, String password) {
        return getUsername().equals(userName) && getPassword().equals(password);
    }

    @Override
    protected boolean isAuthenticating() {
        return true;
    }
}
