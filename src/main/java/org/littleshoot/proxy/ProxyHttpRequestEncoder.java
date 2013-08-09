package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request encoder for the proxy. This is necessary because we need to have 
 * access to the most recent request message on this connection to determine
 * caching rules.
 */
public class ProxyHttpRequestEncoder extends HttpRequestEncoder {

    private static final Logger LOG = 
        LoggerFactory.getLogger(ProxyHttpRequestEncoder.class);
    private final HttpRelayingHandler relayingHandler;
    private final HttpRequestFilter requestFilter;
    private final boolean keepProxyFormat;
    private final boolean transparent;

    /**
     * Creates a new request encoder.
     * 
     * @param handler The class that handles relaying all data along this 
     * connection. We need this to synchronize caching rules for each request
     * and response pair.
     */
    public ProxyHttpRequestEncoder(final HttpRelayingHandler handler) {
        this(handler, null, false);
    }

    /**
     * Creates a new request encoder.
     *
     * @param handler The class that handles relaying all data along this
     * connection. We need this to synchronize caching rules for each request
     * and response pair.
     * @param requestFilter The filter for requests.
     * @param keepProxyFormat keep proxy-formatted URI (used in chaining)
     */
    public ProxyHttpRequestEncoder(final HttpRelayingHandler handler,
                                   final HttpRequestFilter requestFilter,
                                   final boolean keepProxyFormat) {

        this.relayingHandler = handler;
        this.requestFilter = requestFilter;
        this.keepProxyFormat = keepProxyFormat;
        this.transparent = LittleProxyConfig.isTransparent();
    }
    
    @Override
    protected void encode(ChannelHandlerContext ctx, HttpObject msg,
            List<Object> out) throws Exception {
        if (msg instanceof HttpRequest) {
            // The relaying handler needs to know all the headers, including
            // hop-by-hop headers, of the original request, particularly
            // for determining whether or not to close the connection to the
            // browser, so we give it the original and copy the original
            // to modify it just before writing it on the wire.
            final HttpRequest request = (HttpRequest) msg;
            this.relayingHandler.requestEncoded(request);
            
            // Check if we are running in proxy chain mode and modify request 
            // accordingly.
            final HttpRequest toSend;
            if (transparent) {
                toSend = request;
            } else {
                toSend = ProxyUtils.copyHttpRequest(request, keepProxyFormat);
            }
            if (this.requestFilter != null) {
                this.requestFilter.filter(toSend);
            }
            //LOG.info("Writing modified request: {}", httpRequestCopy);
            super.encode(ctx, toSend, out);
        } else {
            super.encode(ctx, msg, out);
        }
    }
}
