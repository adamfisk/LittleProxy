package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP response encoder for the proxy.
 */
public class ProxyHttpResponseEncoder extends HttpResponseEncoder {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final ProxyCacheManager cacheManager;

    /**
     * Creates a new HTTP response encoder that doesn't include responses in 
     * the cache.
     */
    public ProxyHttpResponseEncoder() {
        this(null);
    }
    
    /**
     * Creates a new HTTP response encoder that intercepts the encoding to 
     * include any relevant responses in the cache.
     * 
     * @param cacheManager The class that manages the cache.
     */
    public ProxyHttpResponseEncoder(final ProxyCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }
    
    @Override
    protected Object encode(final ChannelHandlerContext ctx, 
        final Channel channel, final Object msg) throws Exception {
        if (msg instanceof ProxyHttpResponse) {
            //log.info("Processing proxy response!!");
            final ProxyHttpResponse proxyResponse = (ProxyHttpResponse) msg;
            
            // We need the original request and response objects to adequately
            // follow the HTTP caching rules.
            final HttpRequest httpRequest = proxyResponse.getHttpRequest();
            final HttpResponse httpResponse = proxyResponse.getHttpResponse();
            
            // The actual response is either a chunk or a "normal" response.
            final Object response = proxyResponse.getResponse();
            
            // We do this right before encoding because we want to deal with
            // the hop-by-hop headers elsewhere in the proxy processing logic.
            if (response instanceof HttpResponse) {
                final HttpResponse hr = (HttpResponse) response;
                ProxyUtils.stripHopByHopHeaders(hr);
                ProxyUtils.addVia(hr);
                //log.info("Actual response going to browser: {}", hr);
            }
            
            final ChannelBuffer encoded = 
                (ChannelBuffer) super.encode(ctx, channel, response);
            
            // The buffer will be null when it's the last chunk, for example.
            if (encoded != null && this.cacheManager != null) {
                this.cacheManager.cache(httpRequest, httpResponse, response, 
                    encoded);
            }
            
            return encoded;
        }
        return msg;
    }
}
