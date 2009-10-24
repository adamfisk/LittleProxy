package org.littleshoot.proxy;

/**
 * Interface for HTTP proxy implementations.
 */
public interface HttpProxy {

    /**
     * Starts the proxy.
     * 
     * @param address The address to bind to.
     * @param port The port to bind to.
     */
    void start(String address, int port);

}
