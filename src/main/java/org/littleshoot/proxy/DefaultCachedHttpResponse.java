package org.littleshoot.proxy;

import io.netty.buffer.ChannelBuffer;
import io.netty.channel.ChannelFutureListener;

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
        // NOTE: This does not copy the actual bytes.
        return channelBuffer.duplicate();
    }

    public ChannelFutureListener getChannelFutureListener() {
        return channelFutureListener;
    }

}
