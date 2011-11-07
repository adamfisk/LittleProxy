package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequest;

public class DefaultRelayPipelineFactoryFactory 
    implements RelayPipelineFactoryFactory {
    
    private ChainProxyManager chainProxyManager;
    private ChannelGroup channelGroup;
    private HttpRequestFilter requestFilter;
    private final HttpResponseFilters responseFilters;

    public DefaultRelayPipelineFactoryFactory(
        final ChainProxyManager chainProxyManager, 
        final HttpResponseFilters responseFilters, 
        final HttpRequestFilter requestFilter, 
        final ChannelGroup channelGroup) {
        this.chainProxyManager = chainProxyManager;
        this.responseFilters = responseFilters;
        this.channelGroup = channelGroup;
        this.requestFilter = requestFilter;
    }
    
    public ChannelPipelineFactory getRelayPipelineFactory(
        final HttpRequest httpRequest, final Channel browserToProxyChannel,
        final RelayListener relayListener) {
	
        String hostAndPort = chainProxyManager == null
            ? null : chainProxyManager.getChainProxy(httpRequest);
        if (hostAndPort == null) {
            hostAndPort = ProxyUtils.parseHostAndPort(httpRequest);
        }
        
        return new DefaultRelayPipelineFactory(hostAndPort, httpRequest, 
            relayListener, browserToProxyChannel, channelGroup, responseFilters, 
            requestFilter, chainProxyManager);
    }
    
}