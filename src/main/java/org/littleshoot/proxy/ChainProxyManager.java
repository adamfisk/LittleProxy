package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Interface for classes that manage chain proxies.
 */
public interface ChainProxyManager {

    /**
     * 
     * @param httpRequest The HTTP request.
     * @return The Chain Proxy with Host and Port.
     */
    String getChainProxy(HttpRequest httpRequest);
    
    
    /**
     * Callback to report proxy problems
     * @param hostAndPort host and port of the proxy
     */
    void onCommunicationError(String hostAndPort);

}