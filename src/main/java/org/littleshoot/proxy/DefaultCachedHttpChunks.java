package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that keeps track of the cached HTTP chunks for a single HTTP response.
 */
public class DefaultCachedHttpChunks implements CachedHttpChunks {

    public boolean isComplete() {
        // TODO Auto-generated method stub
        return false;
    }

    public boolean writeAllChunks(Channel channel) {
        // TODO Auto-generated method stub
        return false;
    }

    public void cache(HttpChunk chunk, ChannelBuffer encoded) {
        // TODO Auto-generated method stub
        
    }

    /*
    private final Logger log = LoggerFactory.getLogger(getClass());
    private final CacheManager cacheManager;
    private volatile int chunkCount = 0;
    private final String uri;
    private volatile boolean complete = false;
    private final ChannelFutureListener writeListener;

    public DefaultCachedHttpChunks(final CacheManager cacheManager, 
        final HttpRequest httpRequest, 
        final ChannelFutureListener writeListener) {
        this.cacheManager = cacheManager;
        this.writeListener = writeListener;
        this.uri = ProxyUtils.cacheUri(httpRequest);
    }
    
    public void cache(final HttpChunk chunk, final ChannelBuffer encoded) {
        final Cache cache = 
            this.cacheManager.getCache(ProxyConstants.CHUNK_CACHE);
        final String chunkUri = this.uri + chunkCount;

        log.info("Caching chunk {}", chunkUri);
        final Element elem = new Element(chunkUri, encoded);
        cache.put(elem);
        if (chunk.isLast()) {
            this.complete = true;
        }
        chunkCount++;
    }

    public boolean isComplete() {
        return complete;
    }

    public boolean writeAllChunks(final Channel channel) {
        if (!complete) {
            throw new IllegalStateException(
                "Trying to write incomplete cached chunks!!");
        }
        final Cache cache = 
            this.cacheManager.getCache(ProxyConstants.CHUNK_CACHE);
        ChannelFuture cf = null;
        for (int i = 0; i < chunkCount; i++) {
            final String chunkUri = this.uri + i;
            final Element elem = cache.get(chunkUri);
            
            if (elem == null) {
                // This indicates a serious problem. The cache has expelled
                // a chunk in the middle of us trying to write the responses.
                log.error("Could not find chunk!!! {}", chunkUri);
                throw new IllegalStateException("Missing chunk for: "+chunkUri);
            }
            final ChannelBuffer encoded = (ChannelBuffer) elem.getObjectValue();
            cf = channel.write(encoded);
        }
        if (cf == null) {
            log.error("Channel future is null?");
            throw new IllegalStateException("No future? Chunks: "+chunkCount);
        }
        cf.addListener(this.writeListener);
        return true;
    }
    */
}
