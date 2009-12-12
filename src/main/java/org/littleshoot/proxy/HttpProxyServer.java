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
     * Adds a new handler for proxy authentication. Handlers are called in the
     * order they're added. If one handler accepts the user's credentials, it
     * passes them on to the next handler.
     * 
     * @param pah The new authentication handler.
     */
    void addProxyAuthenticationHandler(ProxyAuthorizationHandler pah);

    /**
     * Adds a class for processing responses.
     * 
     * @param responseProcessor The class for processing responses.
     */
    void addResponseProcessor(HttpResponseProcessor responseProcessor);
}
