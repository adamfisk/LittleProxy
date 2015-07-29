package org.littleshoot.proxy;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

import org.apache.commons.lang3.StringUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default cache manager for the proxy.
 */
public class DefaultProxyCacheManager implements ProxyCacheManager {

    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private static final boolean CACHE_ENABLED = false;
    
    private final ExecutorService cacheExecutor = 
        Executors.newSingleThreadExecutor(new ThreadFactory() {
            private int numThreads = 0;
            public Thread newThread(final Runnable r) {
                final Thread t = 
                    new Thread(r, "Cache-Insertion-Thread-"+numThreads);
                numThreads++;
                t.setDaemon(true);
                return t;
            }
        });
    
    public DefaultProxyCacheManager(){} 
    public boolean returnCacheHit(HttpRequest request, Channel channel) {
        // TODO Auto-generated method stub
        return false;
    }
    
    /*
     * 

    private final CacheManager cacheManager = new CacheManager();
    
    public DefaultProxyCacheManager() {
        this.cacheManager.addCache(ProxyConstants.CACHE);
        this.cacheManager.addCache(ProxyConstants.CHUNKS_CACHE);
        this.cacheManager.addCache(ProxyConstants.CHUNK_CACHE);
    }

    public boolean returnCacheHit(final HttpRequest httpRequest, 
        final Channel channel) {
        final String uri = ProxyUtils.cacheUri(httpRequest);
        final Cache cache = this.cacheManager.getCache(ProxyConstants.CACHE);
        final Element elem = cache.get(uri);
        if (elem != null) {
            log.info("Found element in cache for URI: {}", uri);
            final CachedHttpResponse cached = 
                (CachedHttpResponse) elem.getObjectValue();
            final ChannelFutureListener cfl = cached.getChannelFutureListener();
            final ChannelFuture cf = channel.write(cached.getChannelBuffer());
            cf.addListener(cfl);
            log.info("Wrote response from cache!!");
            return true;
        }
        */
        
        
        /*
        final Cache chunkedCache = 
            this.cacheManager.getCache(ProxyConstants.CHUNKS_CACHE);
        final Element chunkedElem = chunkedCache.get(uri);
        if (chunkedElem != null) {
            log.info("Found chunk element in cache");
            final CachedHttpChunks chunker = 
                (CachedHttpChunks) chunkedElem.getObjectValue();
            if (chunker.isComplete()) {
                log.info("Writing all chunks from cache!!");
                return chunker.writeAllChunks(channel);
            }
            else {
                // TODO: Return a 206 partial response with whatever data
                // we have. 
                //
                // See: http://tools.ietf.org/html/rfc2616#section-13.8
            }
        }
        else {
            log.info("No matching element for: {}", uri);
        }
        */
    /*
        return false;
    }
    */
    
    public Future<String> cache(final HttpRequest httpRequest, 
        final HttpResponse httpResponse, final Object response, 
        final ChannelBuffer encoded) {
        
        // We can't depend on the write position and such of the
        // original buffer, so make a duplicate.
        
        // NOTE: This does not copy the actual bytes.
        /*
        final ChannelBuffer copy = encoded.duplicate();
        
        final Callable<String> task = new Callable<String>() {
            public String call() {
                final String uri = ProxyUtils.cacheUri(httpRequest);
                if (!isCacheable(httpRequest, httpResponse)) {
                    log.info("Not cachable: {}", uri);
                    return uri;
                }
                log.info("Response is cacheable -- caching {}", uri);
                
                // We store the ChannelFutureListener so we don't have to
                // keep the request and response objects in memory to 
                // determine what to do after writing the response.
                final ChannelFutureListener cfl = 
                    ProxyUtils.newWriteListener(httpRequest, httpResponse, 
                        response);
                
                if (response instanceof HttpResponse) {
                    final HttpResponse hr = (HttpResponse) response;
                    // We don't currently support caching chunked responses.
                    if (hr.isChunked()) {
                        return uri;
                    }
                    final Cache cache = 
                        cacheManager.getCache(ProxyConstants.CACHE);
                    final CachedHttpResponse cached = 
                        new DefaultCachedHttpResponse(copy, cfl);
                    log.info("Adding to response cache under URI: {}", uri);
                    cache.put(new Element(uri, cached));
                }
//                else if (response instanceof HttpChunk) {
//                    final Cache cache = 
//                        cacheManager.getCache(ProxyConstants.CHUNKS_CACHE);
//                    final CachedHttpChunks cacher;
//                    synchronized (cache) {
//                        final Element chunkedElem = cache.get(uri);
//                        if (chunkedElem != null) {
//                            cacher = 
//                                (CachedHttpChunks) chunkedElem.getObjectValue();
//                        }
//                        else {
//                            cacher = new DefaultCachedHttpChunks(
//                                cacheManager, httpRequest, cfl);
//                            cache.put(new Element(uri, cacher));
//                        }
//                    }
//                    log.info("Adding to chunk cache under URI: {}", uri);
//                    cacher.cache((HttpChunk)response, copy);
//                }
//                else {
//                    log.error("Unknown response type: {}", 
//                        response.getClass());
//                }
                return uri;
            }
        };
        log.info("Submitting task");
        return this.cacheExecutor.submit(task);
        */
        return null;
    }
    
    private boolean isCacheable(final HttpRequest httpRequest, 
        final HttpResponse httpResponse) {
        if (!CACHE_ENABLED) {
            log.info("Cache is not enabled");
            return false;
        }
        final HttpResponseStatus responseStatus = httpResponse.getStatus();
        final boolean cachableStatus;
        final int status = responseStatus.getCode();
        
        // For rules on this, see: 
        // http://tools.ietf.org/html/rfc2616#section-13.4
        //
        // We can't cache 206 responses unless we can support the Range 
        // header in requests. That would be a fantastic extension.
        switch (status) {
            case 200:
            case 203:  
            case 300: 
            case 301:
            case 410:
                cachableStatus = true;
                break;
            default:
                cachableStatus = false;
                break;
        }
        if (!cachableStatus) {
            log.info("HTTP status is not cachable: {}", String.valueOf(status));
            return false;
        }
        
        // Don't use the cache if the request has cookies -- security violation.
        if (httpResponse.headers().contains(HttpHeaders.Names.SET_COOKIE)) {
            log.info("Response contains set cookie header");
            return false;
        }
        if (httpResponse.headers().contains(HttpHeaders.Names.SET_COOKIE2)) {
            log.info("Response contains set cookie2 header");
            return false;
        }
        
        /*
        if (httpRequest.headers().contains(HttpHeaders.Names.COOKIE)) {
            log.info("Request contains Cookie header");
            return false;
        }
        */
        
        final List<String> responseControl = 
            httpResponse.headers().getAll(HttpHeaders.Names.CACHE_CONTROL);
        final List<String> requestControl =
            httpRequest.headers().getAll(HttpHeaders.Names.CACHE_CONTROL);
        final Set<String> cacheControl = new HashSet<String>();
        cacheControl.addAll(requestControl);
        cacheControl.addAll(responseControl);

        
        // We should really follow all the caching rules from:
        // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
        //
        // The effect is not caching some things we could.
        if (!cacheControl.isEmpty()) {
            if (cacheControl.contains(HttpHeaders.Values.NO_CACHE)) {
                log.info("No cache header");
                return false;
            }
            if (cacheControl.contains(HttpHeaders.Values.PRIVATE)) {
                log.info("Private header");
                return false;
            }
            if (cacheControl.contains(HttpHeaders.Values.NO_STORE)) {
                log.info("No store header");
                return false;
            }
            if (cacheControl.contains(HttpHeaders.Values.MUST_REVALIDATE)) {
                log.info("Not caching with 'must revalidate' header");
                return false;
            }
            if (cacheControl.contains(HttpHeaders.Values.PROXY_REVALIDATE)) {
                log.info("Not caching with 'proxy revalidate' header");
                return false;
            }
        }
        
        final String responsePragma = 
            httpResponse.headers().get(HttpHeaders.Names.PRAGMA);
        if (StringUtils.isNotBlank(responsePragma) &&
            responsePragma.contains(HttpHeaders.Values.NO_CACHE)) {
            log.info("Not caching with response pragma no cache");
            return false;
        }
        
        final String requestPragma = 
            httpRequest.headers().get(HttpHeaders.Names.PRAGMA);
        if (StringUtils.isNotBlank(requestPragma) &&
            requestPragma.contains(HttpHeaders.Values.NO_CACHE)) {
            log.info("Not caching with request pragma no cache");
            return false;
        }
        
        log.info("Got cachable response!");
        return true;
    }
}
