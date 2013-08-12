package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;

/**
 * Factory for creating classes that relay responses back to the browser.
 */
public interface HttpRelayingHandlerFactory {

    /**
     * Creates a new relaying handler.
     * 
     * @param browserToProxyChannel The channel from the browser to the proxy
     * server.
     * @param hostAndPort The host and port of the remote server to relay from.
     * @return The new handler.
     */
    ChannelHandler newHandler(Channel browserToProxyChannel, String hostAndPort);
}
