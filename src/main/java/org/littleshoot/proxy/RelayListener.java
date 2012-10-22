package org.littleshoot.proxy;

import java.net.SocketAddress;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Listener for events on the HTTP traffic relayer.
 */
public interface RelayListener {

    void onRelayChannelClose(Channel browserToProxyChannel, SocketAddress address, 
        int unansweredRequests, boolean closedEndsResponseBody);
    
    void onRelayHttpResponse(Channel browserToProxyChannel, SocketAddress address, 
        HttpRequest httpRequest);

    void onChannelAvailable(SocketAddress address, ChannelFuture channelFuture);

}
