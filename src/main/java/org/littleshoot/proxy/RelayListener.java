package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;

/**
 * Listener for events on the HTTP traffic relayer.
 */
public interface RelayListener {

    void onRelayChannelClose(Channel browserToProxyChannel, String hostAndPort);

}
