package org.littleshoot.proxy;

/**
 * Interface for the top-level proxy server class.
 */
public interface HttpProxyServer {

    /**
     * Starts the server.
     */
    void start();
    
    /**
     * Stops the server.
     */
    void stop();
    
    /**
     * Starts the server.
     * 
     * @param localOnly If true, the server will only allow connections from 
     * the local computer. This can significantly improve security in some 
     * cases.
     * @param anyAddress Whether or not to bind to "any" address - 0.0.0.0.
     * This is the default.
     */
    void start(boolean localOnly, boolean anyAddress);

    /**
     * Adds a new handler for proxy authentication. Handlers are called in the
     * order they're added. If one handler accepts the user's credentials, it
     * passes them on to the next handler.
     * 
     * @param pah The new authentication handler.
     */
    void addProxyAuthenticationHandler(ProxyAuthorizationHandler pah);
    
}
