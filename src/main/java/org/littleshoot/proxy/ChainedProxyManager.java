package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.util.Queue;

/**
 * <p>
 * Interface for classes that manage chained proxies.
 * </p>
 */
public interface ChainedProxyManager {

    /**
     * <p>
     * Based on the given httpRequest, add any {@link ChainedProxy}s to the list
     * that should be used to process the request. The upstream proxy will
     * attempt to connect to each of these in the order that they appear until
     * it successfully connects to one.
     * </p>
     * 
     * <p>
     * To allow the proxy to fall back to a direct connection, you can add
     * {@link ChainedProxyAdapter#FALLBACK_TO_DIRECT_CONNECTION} to the end of
     * the list.
     * </p>
     * 
     * @param httpRequest
     * @param chainedProxies
     */
    void lookupChainedProxies(HttpRequest httpRequest,
            Queue<ChainedProxy> chainedProxies);
}