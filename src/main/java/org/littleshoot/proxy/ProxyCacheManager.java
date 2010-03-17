package org.littleshoot.proxy;

import java.util.concurrent.Future;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that handle caching on the proxy.
 */
public interface ProxyCacheManager {

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

    /**
     * Caches the request and response object, if appropriate.
     * 
     * @param originalRequest The original HTTP request.
     * @param httpResponse The HTTP response.
     * @param response The response object. Can be an HttpResponse or an
     * HttpChunk.
     * @param encoded The encoded response to cache.
     * @return The future for when the cache operation is executed.
     */
    Future<String> cache(HttpRequest originalRequest, HttpResponse httpResponse,
        Object response, ChannelBuffer encoded);

}
