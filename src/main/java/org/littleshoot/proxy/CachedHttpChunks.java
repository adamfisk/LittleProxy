package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpChunk;

/**
 * Interface for classes that fetch HTTP chunks from a cache. This is tricky
 * because HTTP chunks typically indicate large response bodies, and if we're
 * not careful we can quickly cause the VM to run out of memory.
 */
public interface CachedHttpChunks {

    boolean isComplete();

    boolean writeAllChunks(Channel channel);

    void cache(HttpChunk chunk, ChannelBuffer encoded);

}
