package org.littleshoot.proxy;

import io.netty.channel.ChannelHandler;

public interface HandshakeHandler {

    ChannelHandler getChannelHandler();

    String getId();

}
