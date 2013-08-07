package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;

public interface InterestOpsListener {

    void channelWritable(ChannelHandlerContext ctx);

}
