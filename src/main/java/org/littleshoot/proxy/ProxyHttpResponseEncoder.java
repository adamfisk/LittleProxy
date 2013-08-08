package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP response encoder for the proxy.
 */
public class ProxyHttpResponseEncoder extends HttpResponseEncoder {

    private final Logger log = LoggerFactory.getLogger(getClass());
    private final boolean transparent;

    /**
     * Creates a new HTTP response encoder.
     * 
     */
    public ProxyHttpResponseEncoder() {
        this(false);
    }

    /**
     * Creates a new HTTP response encoder that intercepts the encoding to 
     * include any relevant responses in the cache.
     * 
     * @param transparent Whether or not this should act as a transparent proxy.
     */
    public ProxyHttpResponseEncoder(final boolean transparent) {
        this.transparent = transparent;
    }
    
    @Override
    protected void encode(ChannelHandlerContext ctx, HttpObject msg,
            List<Object> out) throws Exception {
        if (msg instanceof ProxyHttpResponse) {
            //log.info("Processing proxy response!!");
            final ProxyHttpResponse proxyResponse = (ProxyHttpResponse) msg;
            
            // We need the original request and response objects to adequately
            // follow the HTTP caching rules.
            final HttpRequest httpRequest = proxyResponse.getHttpRequest();
            final HttpResponse httpResponse = proxyResponse.getHttpResponse();
            
            // The actual response is either a chunk or a "normal" response.
            final HttpObject response = proxyResponse.getResponse();
            
            // We do this right before encoding because we want to deal with
            // the hop-by-hop headers elsewhere in the proxy processing logic.
            if (!this.transparent) {
                if (response instanceof HttpResponse) {
                    final HttpResponse hr = (HttpResponse) response;
                    ProxyUtils.stripHopByHopHeaders(hr);
                    ProxyUtils.addVia(hr);
                    //log.info("Actual response going to browser: {}", hr);
                }
            }
            
            super.encode(ctx, response, out);
        } else if (msg instanceof HttpResponse) {
            // We can get an HttpResponse when a third-party is custom 
            // configured, for example.
            if (!this.transparent) {
                final HttpResponse hr = (HttpResponse) msg;
                ProxyUtils.stripHopByHopHeaders(hr);
                ProxyUtils.addVia(hr);
            }
        }
        super.encode(ctx, msg, out);
    }
}
