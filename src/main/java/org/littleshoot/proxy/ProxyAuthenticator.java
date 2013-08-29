package org.littleshoot.proxy;

/**
 * Interface for objects that can authenticate someone for using our Proxy on
 * the basis of a username and password.
 */
public interface ProxyAuthenticator {
    /**
     * Authenticates the user using the specified userName and password.
     * 
     * @param userName
     *            The user name.
     * @param password
     *            The password.
     * @return <code>true</code> if the credentials are acceptable, otherwise
     *         <code>false</code>.
     */
    boolean authenticate(String userName, String password);
}
