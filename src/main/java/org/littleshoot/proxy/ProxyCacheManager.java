package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that handle caching on the proxy.
 */
public interface ProxyCacheManager {

    /**
     * Caches the request and response object, if appropriate.
     * 
     * @param httpRequest The HTTP request.
     * @param httpResponse The HTTP response.
     */
    void cache(HttpRequest httpRequest, HttpResponse httpResponse);

    /**
     * Writes a cached response back to the browser if we have a hit in the
     * cache.
     * 
     * @param request The HTTP request.
     * @param channel The channel the request came in on.
     * @return <code>true</code> if there was a hit in the cache and we
     * returned a response, otherwise <code>false</code>.
     */
    boolean returnCacheHit(HttpRequest request, Channel channel);

}
