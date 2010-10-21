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
     * Starts the server.
     * 
     * @param localOnly If true, the server will only allow connections from 
     * the local computer. This can significantly improve security in some 
     * cases.
     */
    void start(boolean localOnly);

    /**
     * Adds a new handler for proxy authentication. Handlers are called in the
     * order they're added. If one handler accepts the user's credentials, it
     * passes them on to the next handler.
     * 
     * @param pah The new authentication handler.
     */
    void addProxyAuthenticationHandler(ProxyAuthorizationHandler pah);
}
