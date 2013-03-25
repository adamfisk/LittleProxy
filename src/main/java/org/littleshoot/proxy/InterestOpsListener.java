package org.littleshoot.proxy;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;

public interface InterestOpsListener {

    void channelWritable(ChannelHandlerContext ctx, ChannelStateEvent cse);

}
