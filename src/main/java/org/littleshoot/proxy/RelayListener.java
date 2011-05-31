package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Listener for events on the HTTP traffic relayer.
 */
public interface RelayListener {

    void onRelayChannelClose(Channel browserToProxyChannel, String hostAndPort, 
        int unansweredRequests, boolean closedEndsResponseBody);
    
    void onRelayHttpResponse(Channel browserToProxyChannel, String hostAndPort, 
        HttpRequest httpRequest);

    void onChannelAvailable(String hostAndPort, ChannelFuture channelFuture);

}
