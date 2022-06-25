package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.impl.ClientDetails;

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
     * that should be used to process the request. The downstream proxy will
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
     * <p>
     * To keep the proxy from attempting any connection, leave the list blank.
     * This will cause the proxy to return a 502 response.
     * </p>
     */
    void lookupChainedProxies(HttpRequest httpRequest,
                              Queue<ChainedProxy> chainedProxies,
                              ClientDetails clientDetails);
}