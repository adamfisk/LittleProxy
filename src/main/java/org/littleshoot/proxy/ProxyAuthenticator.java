package org.littleshoot.proxy;

/**
 * Interface for objects that can authenticate someone for using our Proxy on
 * the basis of a username and password.
 */
public interface ProxyAuthenticator {

    /**
     * Authenticates the user using the specified proxy authorization header.
     *
     * @param proxyAuthorizationHeaderValue
     *            The proxy authorization header value.
     * @return <code>true</code> if the credential is acceptable, otherwise
     *         <code>false</code>.
     */
    boolean authenticate(String proxyAuthorizationHeaderValue);

    /**
     * Authenticates the user using the specified userName and password.
     * 
     * @param userName
     *            The user name.
     * @param password
     *            The password.
     * @return <code>true</code> if the credentials are acceptable, otherwise
     *         <code>false</code>.
     * @deprecated Use BasicProxyAuthenticator.authenticate to authenticate Basic authorization
     * requests.
     */
    @Deprecated
    boolean authenticate(String userName, String password);
    
    /**
     * The realm value to be used in the request for proxy authentication 
     * ("Proxy-Authenticate" header). Returning null will cause the string
     * "Restricted Files" to be used by default.
     * 
     * @return
     */
    String getRealm();
}
