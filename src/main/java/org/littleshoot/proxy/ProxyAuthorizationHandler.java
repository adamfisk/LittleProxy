package org.littleshoot.proxy;

/**
 * Interface for classes that handle proxy authorization.
 */
public interface ProxyAuthorizationHandler {

    /**
     * Authenticates the user using the specified user name and password.
     * 
     * @param userName The user name.
     * @param password The password.
     * @return <code>true</code> if the credentials are acceptable, otherwise
     * <code>false</code>.
     */
    boolean authenticate(String userName, String password);

}
