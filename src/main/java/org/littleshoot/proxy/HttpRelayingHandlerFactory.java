package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandler;

/**
 * Factory for creating classes that relay responses back to the browser.
 */
public interface HttpRelayingHandlerFactory {

    /**
     * Creates a new relaying handler.
     * 
     * @param browserToProxyChannel The channel from the browser to the proxy
     * server.
     * @return The new handler.
     */
    ChannelHandler newHandler(Channel browserToProxyChannel);
}
