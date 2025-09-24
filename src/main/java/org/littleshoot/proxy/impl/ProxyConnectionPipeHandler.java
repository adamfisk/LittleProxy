package org.littleshoot.proxy.impl;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandler;
import io.netty.channel.ChannelInboundHandlerAdapter;

import static java.util.Objects.requireNonNull;

/**
 * A {@link ChannelInboundHandler} that writes all incoming data to the
 * specified proxy connection.
 */
public class ProxyConnectionPipeHandler extends ChannelInboundHandlerAdapter {
    private final ProxyConnection<?> sink;

    public ProxyConnectionPipeHandler(final ProxyConnection<?> sink) {
        this.sink = requireNonNull(sink, "sink cannot be null");
    }

    @Override
    public void channelRead(final ChannelHandlerContext ctx, final Object msg) {
        sink.channel.writeAndFlush(msg);
    }

    @Override
    public void channelInactive(final ChannelHandlerContext ctx) {
        sink.disconnect();
    }
}
