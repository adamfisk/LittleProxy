package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.util.Map;

import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultRelayPipelineFactory implements ChannelPipelineFactory {
    private static final Logger LOG = 
        LoggerFactory.getLogger(DefaultRelayPipelineFactory.class);
    private static final Timer TIMER = new HashedWheelTimer();
    
    private final String hostAndPort;
    private final HttpRequest httpRequest;
    private final RelayListener relayListener;
    private final Channel browserToProxyChannel;

    private final ChannelGroup channelGroup;
    private final Map<String, HttpFilter> filters;
    private final HttpRequestFilter requestFilter;
    private String chainProxyHostAndPort;
    private final boolean filtersOff;

    
    public DefaultRelayPipelineFactory(final String hostAndPort, 
        final HttpRequest httpRequest, final RelayListener relayListener, 
        final Channel browserToProxyChannel,
        final ChannelGroup channelGroup, final Map<String, HttpFilter> filters, 
        final HttpRequestFilter requestFilter, 
        final String chainProxyHostAndPort) {
        this.hostAndPort = hostAndPort;
        this.httpRequest = httpRequest;
        this.relayListener = relayListener;
        this.browserToProxyChannel = browserToProxyChannel;
        
        this.channelGroup = channelGroup;
        this.filters = filters;
        this.requestFilter = requestFilter;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
        
        this.filtersOff = filters.isEmpty();
    }
    

    public ChannelPipeline getPipeline() throws Exception {
        // Create a default pipeline implementation.
        final ChannelPipeline pipeline = pipeline();
        
        // We always include the request and response decoders
        // regardless of whether or not this is a URL we're 
        // filtering responses for. The reason is that we need to
        // follow connection closing rules based on the response
        // headers and HTTP version. 
        //
        // We also importantly need to follow the cache directives
        // in the HTTP response.
        pipeline.addLast("decoder", 
            new HttpResponseDecoder(8192, 8192*2, 8192*2));
        
        LOG.debug("Querying for host and port: {}", hostAndPort);
        final boolean shouldFilter;
        final HttpFilter filter;
        if (filtersOff) {
            shouldFilter = false;
            filter = null;
        } else {
            filter = filters.get(hostAndPort);
            if (filter == null) {
                LOG.info("Filter not found in: {}", filters);
                shouldFilter = false;
            }
            else {
                LOG.debug("Using filter: {}", filter);
                shouldFilter = filter.shouldFilterResponses(httpRequest);
            }
            LOG.debug("Filtering: "+shouldFilter);
        }
        
        // We decompress and aggregate chunks for responses from 
        // sites we're applying rules to.
        if (shouldFilter) {
            pipeline.addLast("inflater", 
                new HttpContentDecompressor());
            pipeline.addLast("aggregator",            
                new HttpChunkAggregator(filter.getMaxResponseSize()));//2048576));
        }
        
        // The trick here is we need to determine whether or not
        // to cache responses based on the full URI of the request.
        // This request encoder will only get the URI without the
        // host, so we just have to be aware of that and construct
        // the original.
        final HttpRelayingHandler handler;
        if (shouldFilter) {
            handler = new HttpRelayingHandler(browserToProxyChannel, 
                channelGroup, filter, relayListener, hostAndPort);
        } else {
            handler = new HttpRelayingHandler(browserToProxyChannel, 
                channelGroup, relayListener, hostAndPort);
        }
        
        final ProxyHttpRequestEncoder encoder = 
            new ProxyHttpRequestEncoder(handler, requestFilter, 
                chainProxyHostAndPort);
        pipeline.addLast("encoder", encoder);
        
        // We close idle connections to remote servers after the
        // specified timeouts in seconds. If we're sending data, the
        // write timeout should be reasonably low. If we're reading
        // data, however, the read timeout is more relevant.
        final int readTimeoutSeconds;
        final int writeTimeoutSeconds;
        if (httpRequest.getMethod().equals(HttpMethod.POST) ||
            httpRequest.getMethod().equals(HttpMethod.PUT)) {
            readTimeoutSeconds = 0;
            writeTimeoutSeconds = 40;
        } else {
            readTimeoutSeconds = 40;
            writeTimeoutSeconds = 0;
        }
        pipeline.addLast("idle", 
            new IdleStateHandler(TIMER, readTimeoutSeconds, 
                writeTimeoutSeconds, 0));
        pipeline.addLast("idleAware", new IdleAwareHandler());
        pipeline.addLast("handler", handler);
        return pipeline;
    }
}