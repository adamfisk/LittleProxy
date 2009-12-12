package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;

/**
 * Factory for creating new 
 */
public class DefaultHttpRelayingHandlerFactory implements
    HttpRelayingHandlerFactory {

    private final ChannelGroup allChannels;
    private final HttpResponseProcessorManager responseProcessorManager;

    public DefaultHttpRelayingHandlerFactory(final ChannelGroup allChannels,
        final HttpResponseProcessorManager responseProcessorManager) {
        this.allChannels = allChannels;
        this.responseProcessorManager = responseProcessorManager;
    }

    public ChannelHandler newHandler(final Channel browserToProxyChannel) {
        return new HttpRelayingHandler(browserToProxyChannel, 
           this.allChannels, this.responseProcessorManager);
    }

}
