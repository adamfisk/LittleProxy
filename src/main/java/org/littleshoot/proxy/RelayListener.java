package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Listener for events on the HTTP traffic relayer.
 */
public interface RelayListener {

    void onRelayChannelClose(Channel browserToProxyChannel, String hostAndPort, 
        int unansweredRequests);
    
    void onRelayHttpResponse(Channel browserToProxyChannel, String hostAndPort, 
        HttpRequest httpRequest);

}
