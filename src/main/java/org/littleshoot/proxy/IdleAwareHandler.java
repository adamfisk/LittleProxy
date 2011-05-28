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

    @Override
    public void channelIdle(ChannelHandlerContext ctx, IdleStateEvent e) {
        if (e.getState() == IdleState.READER_IDLE) {
            log.info("Got reader idle -- closing");
            e.getChannel().close();
        } else if (e.getState() == IdleState.WRITER_IDLE) {
            log.info("Got writer idle -- closing connection");
            e.getChannel().close();
        }
    }
}
