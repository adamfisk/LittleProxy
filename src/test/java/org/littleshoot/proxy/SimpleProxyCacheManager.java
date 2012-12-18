package org.littleshoot.proxy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.handler.codec.http.HttpRequest;

class SimpleProxyCacheManager implements ProxyCacheManager {

    public static final List<String> requests = new ArrayList<String>();
    
    public boolean returnCacheHit(HttpRequest request, Channel channel) {
        
        requests.add( request.getUri() );
        
        return false;
    }

    public Future<String> cache(HttpRequest originalRequest,
            org.jboss.netty.handler.codec.http.HttpResponse httpResponse,
            Object response, ChannelBuffer encoded) {
        
        return null;
    }
    
}