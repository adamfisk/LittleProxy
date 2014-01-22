package org.littleshoot.proxy.impl;

import org.littleshoot.proxy.ProxyAuthenticator;

/**
 * Basic authenticator with static username and password
 */
public class StaticProxyAuthenticator implements ProxyAuthenticator {
    private final String userName;

    private final String password;

    private final String realm;

    public StaticProxyAuthenticator(String userName, String password, String realm) {
        super();
        this.userName = userName;
        this.password = password;
        this.realm = realm;
    }

    @Override
    public boolean authenticate(String userName, String password) {
        return this.userName.equals(userName) && this.password.equals(password);
    }

    @Override
    public String getRealm() {
        return realm;
    }
}
