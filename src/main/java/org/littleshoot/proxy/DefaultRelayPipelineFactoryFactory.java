package org.littleshoot.proxy;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequest;

public class DefaultRelayPipelineFactoryFactory 
    implements RelayPipelineFactoryFactory {
    
    protected ChainProxyManager chainProxyManager;
    protected ChannelGroup channelGroup;
    protected HttpRequestFilter requestFilter;
    protected final HttpResponseFilters responseFilters;

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
    
    @Override
    public ChannelPipelineFactory getRelayPipelineFactory(
        final HttpRequest httpRequest, final Channel browserToProxyChannel,
        final RelayListener relayListener, final ChannelHandlerContext ctx,
        String hostAndPort) {
	
        return getRelayPipelineFactory(httpRequest, 
            browserToProxyChannel, relayListener, hostAndPort, ctx);
    }
    
    protected DefaultRelayPipelineFactory getRelayPipelineFactory(
              final HttpRequest httpRequest, final Channel browserToProxyChannel,
              final RelayListener relayListener, final String hostAndPort,
              final ChannelHandlerContext ctx) {
        return new DefaultRelayPipelineFactory(hostAndPort, httpRequest,
            relayListener, browserToProxyChannel, channelGroup, responseFilters,
            requestFilter, chainProxyManager);
    }
}
