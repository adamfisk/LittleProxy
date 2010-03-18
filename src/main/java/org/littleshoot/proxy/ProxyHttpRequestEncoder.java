package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;

/**
 * Request encoder for the proxy. This is necessary because we need to have 
 * access to the most recent request message on this connection to determine
 * caching rules.
 */
public class ProxyHttpRequestEncoder extends HttpRequestEncoder {

    private final HttpRelayingHandler relayingHandler;

    /**
     * Creates a new request encoder.
     * 
     * @param handler The class that handles relaying all data along this 
     * connection. We need this to synchronize caching rules for each request
     * and response pair.
     */
    public ProxyHttpRequestEncoder(final HttpRelayingHandler handler) {
        this.relayingHandler = handler;
    }

    @Override
    protected Object encode(final ChannelHandlerContext ctx, 
        final Channel channel, final Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            this.relayingHandler.requestEncoded((HttpRequest) msg);
        }
        return super.encode(ctx, channel, msg);
    }
}
