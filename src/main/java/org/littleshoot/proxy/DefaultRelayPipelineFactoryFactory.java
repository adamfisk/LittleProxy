package org.littleshoot.proxy;

import java.net.SocketAddress;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.util.Timer;

public class DefaultRelayPipelineFactoryFactory implements RelayPipelineFactoryFactory {
    
    private final ChainProxyManager chainProxyManager;
    private final ChannelGroup channelGroup;
    private final HttpRequestFilter requestFilter;
    private final HttpResponseFilters responseFilters;
    private final Timer timer;

    public DefaultRelayPipelineFactoryFactory(
        ChainProxyManager chainProxyManager, 
        HttpResponseFilters responseFilters, 
        HttpRequestFilter requestFilter, 
        ChannelGroup channelGroup, Timer timer) {
        
        this.chainProxyManager = chainProxyManager;
        this.responseFilters = responseFilters;
        this.channelGroup = channelGroup;
        this.requestFilter = requestFilter;
        this.timer = timer;
    }
    
    @Override
    public ChannelPipelineFactory getRelayPipelineFactory(
        HttpRequest httpRequest, Channel browserToProxyChannel,
        RelayListener relayListener) throws Exception {
	
        SocketAddress address = chainProxyManager.getChainProxy(httpRequest);
        
        return new DefaultRelayPipelineFactory(address, httpRequest, 
            relayListener, browserToProxyChannel, channelGroup, responseFilters, 
            requestFilter, chainProxyManager, this.timer);
    }
    
}