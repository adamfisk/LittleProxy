package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;
import org.jboss.netty.channel.group.ChannelGroup;

/**
 * Factory for creating new classes for relaying data from remote sites to 
 * the browsers hitting the proxy.
 */
public class DefaultHttpRelayingHandlerFactory implements
    HttpRelayingHandlerFactory {

    private final ChannelGroup allChannels;

    /**
     * Creates a new factory for creating classes for relaying data from 
     * remote sites to the browsers hitting the proxy.
     * 
     * @param allChannels {@link ChannelGroup} for keeping track of all 
     * channels so we can close them.
     */
    public DefaultHttpRelayingHandlerFactory(final ChannelGroup allChannels) {
        this.allChannels = allChannels;
    }

    public ChannelHandler newHandler(final Channel browserToProxyChannel, 
        final String hostAndPort) {
        return new HttpRelayingHandler(browserToProxyChannel, this.allChannels);
    }

}
