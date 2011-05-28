package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.handler.codec.http.HttpRequest;

public interface RelayPipelineFactoryFactory {

    ChannelPipelineFactory getRelayPipelineFactory(HttpRequest httpRequest, 
        Channel browserToProxyChannel, RelayListener relayListener);

}
