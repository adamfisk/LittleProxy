package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
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
    private final String chainProxyHostAndPort;

    /**
     * Creates a new request encoder.
     * 
     * @param handler The class that handles relaying all data along this 
     * connection. We need this to synchronize caching rules for each request
     * and response pair.
     * @param chainProxyHostAndPort The configured proxy chain host and port.
     * @param requestFilter The filter for requests.
     */
    public ProxyHttpRequestEncoder(final HttpRelayingHandler handler, 
        final HttpRequestFilter requestFilter, 
        final String chainProxyHostAndPort) {
        this.relayingHandler = handler;
        this.requestFilter = requestFilter;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
    }

    @Override
    protected Object encode(final ChannelHandlerContext ctx, 
        final Channel channel, final Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            // The relaying handler needs to know all the headers, including
            // hop-by-hop headers, of the original request, particularly
            // for determining whether or not to close the connection to the
            // browser, so we give it the original and modify the original
            // just before writing it on the wire.
            final HttpRequest request = (HttpRequest) msg;
            this.relayingHandler.requestEncoded(request);
            
            // Check if we are running in proxy chain mode and modify request 
            // accordingly
            final HttpRequest httpRequestCopy = 
                ProxyUtils.copyHttpRequest(request, 
                this.chainProxyHostAndPort != null);
            
            if (this.requestFilter != null) {
                this.requestFilter.filter(httpRequestCopy);
            }
            //LOG.info("Sending modified request headers:");
            //ProxyUtils.printHeaders(httpRequestCopy);
            return super.encode(ctx, channel, httpRequestCopy);
        }
        return super.encode(ctx, channel, msg);
    }
}
