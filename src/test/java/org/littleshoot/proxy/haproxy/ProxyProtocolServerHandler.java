package org.littleshoot.proxy.haproxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.haproxy.HAProxyMessage;

public class ProxyProtocolServerHandler extends ChannelInboundHandlerAdapter {

    private HAProxyMessage haProxyMessage;

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if ( msg instanceof HAProxyMessage){
            this.haProxyMessage = (HAProxyMessage) msg;
        }
    }

    HAProxyMessage getHaProxyMessage() {
        return haProxyMessage;
    }
}
