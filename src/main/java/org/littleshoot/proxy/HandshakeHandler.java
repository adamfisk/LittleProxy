package org.littleshoot.proxy;

import org.jboss.netty.channel.ChannelHandler;

public interface HandshakeHandler {

    ChannelHandler getChannelHandler();

    String getId();

}
