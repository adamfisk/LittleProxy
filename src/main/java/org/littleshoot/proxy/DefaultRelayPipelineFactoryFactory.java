package org.littleshoot.proxy;

import java.util.Map;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequest;

public class DefaultRelayPipelineFactoryFactory 
    implements RelayPipelineFactoryFactory {
    
    private String chainProxyHostAndPort;
    private Map<String, HttpFilter> filters;
    private ChannelGroup channelGroup;
    private HttpRequestFilter requestFilter;

    public DefaultRelayPipelineFactoryFactory(
        final String chainProxyHostAndPort, 
        final Map<String, HttpFilter> filters, HttpRequestFilter requestFilter, 
        final ChannelGroup channelGroup) {
        this.chainProxyHostAndPort = chainProxyHostAndPort;
        this.filters = filters;
        this.channelGroup = channelGroup;
        this.requestFilter = requestFilter;
    }
    
    public ChannelPipelineFactory getRelayPipelineFactory(
        final HttpRequest httpRequest, final Channel browserToProxyChannel, 
        final RelayListener relayListener) {
        final String hostAndPort = 
            chainProxyHostAndPort != null ? chainProxyHostAndPort : 
                ProxyUtils.parseHostAndPort(httpRequest);
        
        return new DefaultRelayPipelineFactory(hostAndPort, httpRequest, 
            relayListener, browserToProxyChannel, channelGroup, filters, 
            requestFilter, chainProxyHostAndPort);
    }
    
}