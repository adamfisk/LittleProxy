package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpRequest;

public class DefaultRelayChannelInitializerFactory 
    implements RelayChannelInitializerFactory {
    
    private final ChainProxyManager chainProxyManager;
    private final ChannelGroup channelGroup;
    private final HttpRequestFilter requestFilter;
    private final HttpResponseFilters responseFilters;

    public DefaultRelayChannelInitializerFactory(
        final ChainProxyManager chainProxyManager, 
        final HttpResponseFilters responseFilters, 
        final HttpRequestFilter requestFilter, 
        final ChannelGroup channelGroup) {
        this.chainProxyManager = chainProxyManager;
        this.responseFilters = responseFilters;
        this.channelGroup = channelGroup;
        this.requestFilter = requestFilter;
    }
    
    public ChannelInitializer<Channel> getRelayChannelInitializer(
        final HttpRequest httpRequest, final Channel browserToProxyChannel,
        final RelayListener relayListener) {
	
        String hostAndPort = chainProxyManager == null
            ? null : chainProxyManager.getChainProxy(httpRequest);
        if (hostAndPort == null) {
            hostAndPort = ProxyUtils.parseHostAndPort(httpRequest);
        }
        
        return new DefaultRelayChannelInitializer(hostAndPort, httpRequest, 
            relayListener, browserToProxyChannel, channelGroup, responseFilters, 
            requestFilter, chainProxyManager);
    }
    
}