package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import org.junit.Assert;
import org.littleshoot.proxy.impl.ClientDetails;

import java.util.Queue;

/**
 * Tests a single proxy that requires username/password authentication.
 */
public class AuthenticatingProxyWithChainingTest extends BaseProxyTest
        implements ProxyAuthenticator, ChainedProxyManager {

    private ClientDetails savedClientDetails;

    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(0)
                .withProxyAuthenticator(this)
                .withChainProxyManager(this)
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

    @Override
    public String getRealm() {
        return null;
    }

    @Override
    public void lookupChainedProxies(HttpRequest httpRequest, Queue<ChainedProxy> chainedProxies, ClientDetails clientDetails) {
        savedClientDetails = clientDetails;
        chainedProxies.add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        Assert.assertEquals(getUsername(), savedClientDetails.getUserName());
        Assert.assertTrue(savedClientDetails.getClientAddress().getAddress().isLoopbackAddress());
    }
}
