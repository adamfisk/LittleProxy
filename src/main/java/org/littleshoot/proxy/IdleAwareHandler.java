package org.littleshoot.proxy;

import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This handles idle sockets.
 */
public class IdleAwareHandler extends IdleStateAwareChannelHandler {

    private static final Logger log = 
        LoggerFactory.getLogger(IdleAwareHandler.class);
    private final String handlerName;
    
    public IdleAwareHandler(final String handlerName) {
        this.handlerName = handlerName;
    }

    @Override
    public void channelIdle(final ChannelHandlerContext ctx, 
        final IdleStateEvent e) {
        if (e.getState() == IdleState.READER_IDLE) {
            log.info("Got reader idle -- closing -- "+this);
            e.getChannel().close();
        } else if (e.getState() == IdleState.WRITER_IDLE) {
            log.info("Got writer idle -- closing connection -- "+this);
            e.getChannel().close();
        }
    }
    
    @Override
    public String toString() {
        return "IdleAwareHandler [handlerName=" + handlerName + "]";
    }
}
