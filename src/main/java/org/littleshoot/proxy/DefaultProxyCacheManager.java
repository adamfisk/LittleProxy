package org.littleshoot.proxy;

import net.sf.ehcache.Cache;
import net.sf.ehcache.CacheManager;
import net.sf.ehcache.Element;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
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
    private final CacheManager cacheManager = new CacheManager();
    
    private final boolean CACHE_ENABLED = false;
    
    /**
     * Creates a new cache manager.
     */
    public DefaultProxyCacheManager() {
        this.cacheManager.addCache(ProxyConstants.CACHE);
    }
    
    public void cache(final HttpRequest httpRequest, 
        final HttpResponse httpResponse) {
        log.info("Considering whether or not to cache...");
        final Cache cache = this.cacheManager.getCache(ProxyConstants.CACHE);
        if (isCacheable(httpResponse)) {
            log.info("Response is cacheable -- caching");
            final String uri = httpRequest.getUri();
            
            // TODO: Make a copy of the response? Otherwise it won't correctly
            // "play back" on successive hits I don't think.
            cache.put(new Element(uri, httpResponse));
        }
    }

    private boolean isCacheable(final HttpResponse httpResponse) {
        if (!CACHE_ENABLED) {
            return false;
        }
        final HttpResponseStatus status = httpResponse.getStatus();
        final boolean ok = status.equals(HttpResponseStatus.OK) ||
            status.equals(HttpResponseStatus.PARTIAL_CONTENT);
        // TODO: Think more about any other statuses it might make sense to
        // cache.
        if (!ok) {
            return false;
        }
        // Don't use the cache unless the Cache-control header is "public".
        final String cacheControl = 
            httpResponse.getHeader(HttpHeaders.Names.CACHE_CONTROL);
        if (StringUtils.isBlank(cacheControl)) {
            return false;
        }
        // We should really follow all the caching rules from:
        // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html
        //
        // The effect is not caching some things we could.
        if (!cacheControl.toLowerCase().contains(HttpHeaders.Values.PUBLIC)) {
            return false;
        }
        
        // Don't use the cache if the request has cookies -- security violation.
        final String cookies = httpResponse.getHeader(HttpHeaders.Names.COOKIE);
        if (StringUtils.isNotBlank(cookies)) {
            return false;
        }
        return true;
    }

    public boolean returnCacheHit(final HttpRequest hr, final Channel channel) {
        final String uri = hr.getUri();
        final Cache cache = this.cacheManager.getCache(ProxyConstants.CACHE);
        final Element elem = cache.get(uri);
        if (elem != null) {
            final HttpResponse response = (HttpResponse) elem.getObjectValue();

            final ChannelFuture cf = channel.write(response);
            ProxyUtils.addListenerForResponse(cf, hr, response, response);
            return true;
        }
        return false;
    }
}
