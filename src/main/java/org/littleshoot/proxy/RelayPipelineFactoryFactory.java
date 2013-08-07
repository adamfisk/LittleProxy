package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.handler.codec.http.HttpRequest;

public interface RelayPipelineFactoryFactory {

    ChannelInitializer<Channel> getRelayChannelInitializer(HttpRequest httpRequest, 
        Channel browserToProxyChannel, RelayListener relayListener);

}
