package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.timeout.IdleStateHandler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating channels for relaying data from external servers to
 * clients.
 */
public class DefaultRelayChannelInitializer extends ChannelInitializer<Channel> {
    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultRelayChannelInitializer.class);

    private final String hostAndPort;
    private final HttpRequest httpRequest;
    private final RelayListener relayListener;
    private final Channel browserToProxyChannel;

    private final ChannelGroup channelGroup;
    private final HttpRequestFilter requestFilter;
    private final ChainProxyManager chainProxyManager;
    private final boolean filtersOff;
    private final HttpResponseFilters responseFilters;

    public DefaultRelayChannelInitializer(final String hostAndPort,
            final HttpRequest httpRequest, final RelayListener relayListener,
            final Channel browserToProxyChannel,
            final ChannelGroup channelGroup,
            final HttpResponseFilters responseFilters,
            final HttpRequestFilter requestFilter,
            final ChainProxyManager chainProxyManager) {
        this.hostAndPort = hostAndPort;
        this.httpRequest = httpRequest;
        this.relayListener = relayListener;
        this.browserToProxyChannel = browserToProxyChannel;

        this.channelGroup = channelGroup;
        this.responseFilters = responseFilters;
        this.requestFilter = requestFilter;
        this.chainProxyManager = chainProxyManager;

        this.filtersOff = responseFilters == null;
    }

    @Override
    protected void initChannel(Channel ch) throws Exception {
        final ChannelPipeline pipeline = ch.pipeline();

        // We always include the request and response decoders
        // regardless of whether or not this is a URL we're
        // filtering responses for. The reason is that we need to
        // follow connection closing rules based on the response
        // headers and HTTP version.
        //
        // We also importantly need to follow the cache directives
        // in the HTTP response.
        final HttpResponseDecoder decoder;
        if (httpRequest.getMethod() == HttpMethod.HEAD) {
            decoder = new HttpResponseDecoder(8192, 8192 * 2, 8192 * 2) {
                @Override
                protected boolean isContentAlwaysEmpty(final HttpMessage msg) {
                    return true;
                }
            };
        } else {
            decoder = new HttpResponseDecoder(8192, 8192 * 2, 8192 * 2);
        }
        pipeline.addLast("decoder", decoder);

        LOG.debug("Querying for host and port: {}", hostAndPort);
        final boolean shouldFilter;
        final HttpFilter filter;
        if (filtersOff) {
            shouldFilter = false;
            filter = null;
        } else {
            filter = responseFilters.getFilter(hostAndPort);
            if (filter == null) {
                LOG.info("No filter found");
                shouldFilter = false;
            } else {
                LOG.debug("Using filter: {}", filter);
                shouldFilter = filter.filterResponses(httpRequest);
                // We decompress and aggregate chunks for responses from
                // sites we're applying rules to.
                if (shouldFilter) {
                    pipeline.addLast("inflater", new HttpContentDecompressor());
                    pipeline.addLast("aggregator", new HttpObjectAggregator(
                            filter.getMaxResponseSize()));// 2048576));
                }
            }
            LOG.debug("Filtering: " + shouldFilter);
        }

        final HttpRelayingHandler handler;
        if (shouldFilter) {
            LOG.info("Creating relay handler with filter");
            handler = new HttpRelayingHandler(browserToProxyChannel,
                    channelGroup, filter, relayListener, hostAndPort);
        } else {
            LOG.info("Creating non-filtering relay handler");
            handler = new HttpRelayingHandler(browserToProxyChannel,
                    channelGroup, relayListener, hostAndPort);
        }

        final ProxyHttpRequestEncoder encoder = new ProxyHttpRequestEncoder(
                handler, requestFilter, chainProxyManager != null
                        && chainProxyManager.getChainProxy(httpRequest) != null);
        pipeline.addLast("encoder", encoder);

        // We close idle connections to remote servers after the
        // specified timeouts in seconds. If we're sending data, the
        // write timeout should be reasonably low. If we're reading
        // data, however, the read timeout is more relevant.
        final HttpMethod method = httpRequest.getMethod();

        // Could be any protocol if it's connect, so hard to say what the
        // timeout should be, if any.
        if (!method.equals(HttpMethod.CONNECT)) {
            final int readTimeoutSeconds;
            final int writeTimeoutSeconds;
            if (method.equals(HttpMethod.POST) || method.equals(HttpMethod.PUT)) {
                readTimeoutSeconds = 0;
                writeTimeoutSeconds = 70;
            } else {
                readTimeoutSeconds = 70;
                writeTimeoutSeconds = 0;
            }
            pipeline.addLast("idle", new IdleStateHandler(readTimeoutSeconds,
                    writeTimeoutSeconds, 0));
        }
        pipeline.addLast("handler", handler);
    }
}