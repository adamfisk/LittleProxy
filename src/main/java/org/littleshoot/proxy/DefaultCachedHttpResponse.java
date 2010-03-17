package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.ChannelFutureListener;

/**
 * Default implementation for storing HTTP response data in the cache.
 */
public class DefaultCachedHttpResponse implements CachedHttpResponse {

    private final ChannelBuffer channelBuffer;
    private final ChannelFutureListener channelFutureListener;

    /**
     * Creates a new cached response.
     * 
     * @param channelBuffer The channel buffer with data to write.
     * @param channelFutureListener The class for listening to write events
     * that takes appropriate actions such as closing the connection.
     */
    public DefaultCachedHttpResponse(final ChannelBuffer channelBuffer,
        final ChannelFutureListener channelFutureListener) {
        this.channelBuffer = channelBuffer;
        this.channelFutureListener = channelFutureListener;
    }

    public ChannelBuffer getChannelBuffer() {
        // We can never return the original buffer because multiple threads 
        // could then access it and modify the mutable data.
        final ChannelBuffer cb = channelBuffer.duplicate();
        cb.clear();
        return cb;
    }

    public ChannelFutureListener getChannelFutureListener() {
        return channelFutureListener;
    }

}
