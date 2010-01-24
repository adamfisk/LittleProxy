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
    private final HttpResponseProcessorFactory responseProcessorFactory;

    public DefaultHttpRelayingHandlerFactory(final ChannelGroup allChannels,
        final HttpResponseProcessorFactory responseProcessorFactory) {
        this.allChannels = allChannels;
        this.responseProcessorFactory = responseProcessorFactory;
    }

    public ChannelHandler newHandler(final Channel browserToProxyChannel, 
        final String hostAndPort) {
        final HttpResponseProcessor processor = 
            responseProcessorFactory.newProcessor();
        return new HttpRelayingHandler(browserToProxyChannel, 
           this.allChannels, processor, hostAndPort);
    }

}
