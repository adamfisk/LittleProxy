package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;

/**
 * Listener for events on the HTTP traffic relayer.
 */
public interface RelayListener {

    void onRelayChannelClose(ChannelHandlerContext ctx, ChannelStateEvent e,
            Channel browserToProxyChannel, String hostAndPort);

}
