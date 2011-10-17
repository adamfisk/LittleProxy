package org.littleshoot.proxy;

import java.util.Map;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequest;

public class DefaultRelayPipelineFactoryFactory 
    implements RelayPipelineFactoryFactory {
    
    private ChainProxyManager chainProxyManager;
    private Map<String, HttpFilter> filters;
    private ChannelGroup channelGroup;
    private HttpRequestFilter requestFilter;

    public DefaultRelayPipelineFactoryFactory(
        final ChainProxyManager chainProxyManager, 
        final Map<String, HttpFilter> filters, 
        final HttpRequestFilter requestFilter, 
        final ChannelGroup channelGroup) {
        this.chainProxyManager = chainProxyManager;
        this.filters = filters;
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
            relayListener, browserToProxyChannel, channelGroup, filters, 
            requestFilter, chainProxyManager);
    }
    
}