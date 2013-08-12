package org.littleshoot.proxy;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.timeout.IdleState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This handles idle sockets.
 */
public class IdleAwareHandler extends ChannelDuplexHandler {

    private static final Logger log = 
        LoggerFactory.getLogger(IdleAwareHandler.class);
    private final String handlerName;
    
    public IdleAwareHandler(final String handlerName) {
        this.handlerName = handlerName;
    }
    
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
        if (evt instanceof IdleState) {
            this.channelIdle(ctx, (IdleState) evt);
        }
    }
    
    protected void channelIdle(ChannelHandlerContext ctx, IdleState idleState) {
        if (idleState == IdleState.READER_IDLE) {
            log.info("Got reader idle -- closing -- " + this);
            ctx.channel().close();
        } else if (idleState == IdleState.WRITER_IDLE) {
            log.info("Got writer idle -- closing connection -- " + this);
            ctx.channel().close();
        }
    }

    @Override
    public String toString() {
        return "IdleAwareHandler [handlerName=" + handlerName + "]";
    }
}
