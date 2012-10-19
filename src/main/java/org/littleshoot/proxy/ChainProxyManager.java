package org.littleshoot.proxy;

import java.net.SocketAddress;

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
    SocketAddress getChainProxy(HttpRequest httpRequest) throws Exception;
    
    
    /**
     * Callback to report proxy problems
     * @param hostAndPort host and port of the proxy
     */
    boolean onCommunicationError(SocketAddress address, Throwable cause);

}