package org.littleshoot.proxy;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.ChannelFutureListener;

/**
 * HTTP response object stored in the cache.
 */
public interface CachedHttpResponse {

    /**
     * Accessor for the raw data for the response.
     * 
     * @return The raw data for the response.
     */
    ChannelBuffer getChannelBuffer();

    /**
     * Accessor for the listener for once the response is written. This will
     * take the appropriate action based on HTTP rules, such as closing the
     * connection.
     * 
     * @return The class for listening for write events on the completed 
     * response.
     */
    ChannelFutureListener getChannelFutureListener();
}
