package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;

public class ProxyHttpRequestEncoder extends HttpRequestEncoder {
    
    private HttpRequest httpMessage;

    @Override
    protected Object encode(final ChannelHandlerContext ctx, 
        final Channel channel, final Object msg) throws Exception {
        if (msg instanceof HttpRequest) {
            this.httpMessage = (HttpRequest) msg;
        }
        return super.encode(ctx, channel, msg);
    }

    public HttpRequest getHttpRequest() {
        return httpMessage;
    }
}
