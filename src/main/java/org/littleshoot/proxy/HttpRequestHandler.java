package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for handling all HTTP requests from the browser to the proxy.
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {

    private final static Logger log = 
        LoggerFactory.getLogger(HttpRequestHandler.class);
    private volatile boolean readingChunks;
    
    private static int totalBrowserToProxyConnections = 0;
    private int browserToProxyConnections = 0;
    
    private final Map<String, ChannelFuture> endpointsToChannelFutures = 
        new ConcurrentHashMap<String, ChannelFuture>();
    
    private volatile int messagesReceived = 0;
    private final ProxyAuthorizationManager authorizationManager;
    
    /**
     * Note, we *can* receive requests for multiple different sites from the
     * same connection from the browser, so the host and port most certainly
     * does change.
     * 
     * Why do we need to store it? We need it to lookup the appropriate 
     * external connection to send HTTP chunks to.
     */
    private String hostAndPort;
    private final String chainProxyHostAndPort;
    private final ChannelGroup channelGroup;

    /**
     * {@link Map} of host name and port strings to filters to apply.
     */
    private final Map<String, HttpFilter> filters;
    private final ClientSocketChannelFactory clientChannelFactory;
    private final ProxyCacheManager cacheManager;
    
    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * 
     * @param cacheManager The manager for the cache. 
     * @param authorizationManager The class that handles any 
     * proxy authentication requirements.
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param filters HTTP filtering rules.
     * @param clientChannelFactory The common channel factory for clients.
     * @param chainProxyHostAndPort upstream proxy server host and port or null 
     * if none used.
     */
    public HttpRequestHandler(final ProxyCacheManager cacheManager, 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters,
        final ClientSocketChannelFactory clientChannelFactory,
        final String chainProxyHostAndPort) {
        this.cacheManager = cacheManager;
        this.authorizationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.filters = filters;
        this.clientChannelFactory = clientChannelFactory;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
    }

    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * 
     * @param cacheManager The manager for the cache. 
     * @param authorizationManager The class that handles any 
     * proxy authentication requirements.
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param filters HTTP filtering rules.
     * @param clientChannelFactory The common channel factory for clients.
     */
    public HttpRequestHandler(final ProxyCacheManager cacheManager, 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters,
        final ClientSocketChannelFactory clientChannelFactory) {
        this(cacheManager, authorizationManager, channelGroup, filters, 
            clientChannelFactory, null);
    }
    
    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        messagesReceived++;
        log.info("Received "+messagesReceived+" total messages");
        if (!readingChunks) {
            processMessage(ctx, me);
        } 
        else {
            processChunk(ctx, me);
        }
    }

    private void processChunk(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        log.info("Processing chunk...");
        final HttpChunk chunk = (HttpChunk) me.getMessage();
        
        // Remember this will typically be a persistent connection, so we'll
        // get another request after we're read the last chunk. So we need to
        // reset it back to no longer read in chunk mode.
        if (chunk.isLast()) {
            this.readingChunks = false;
        }
        final ChannelFuture cf = 
            endpointsToChannelFutures.get(hostAndPort);
        
        // We don't necessarily know the channel is connected yet!! This can
        // happen if the client sends a chunk directly after the initial 
        // request.
        if (cf.getChannel().isConnected()) {
            cf.getChannel().write(chunk);
        }
        else {
            cf.addListener(new ChannelFutureListener() {
                
                public void operationComplete(final ChannelFuture future) 
                    throws Exception {
                    cf.getChannel().write(chunk);
                }
            });
        }
    }

    private void processMessage(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        
        if (this.cacheManager.returnCacheHit((HttpRequest)me.getMessage(), 
            me.getChannel())) {
            log.info("Found cache hit! Cache wrote the response.");
            return;
        }
        
        final HttpRequest request = (HttpRequest) me.getMessage();
        
        log.info("Got request: {} on channel: "+me.getChannel(), request);
        if (!this.authorizationManager.handleProxyAuthorization(request, ctx)) {
            log.info("Not authorized!!");
            return;
        }
        
        // Check if we are running in proxy chain mode and modify request 
        // accordingly
        final HttpRequest httpRequestCopy = ProxyUtils.copyHttpRequest(request, 
            this.chainProxyHostAndPort != null);
        if (this.chainProxyHostAndPort != null) {
            this.hostAndPort = this.chainProxyHostAndPort;
        } else {
            this.hostAndPort = ProxyUtils.parseHostAndPort(request);
        }
        
        final Channel inboundChannel = me.getChannel();
        
        final class OnConnect {
            public ChannelFuture onConnect(final ChannelFuture cf) {
                if (httpRequestCopy.getMethod() != HttpMethod.CONNECT) {
                    return cf.getChannel().write(httpRequestCopy);
                }
                else {
                    writeConnectResponse(ctx, request, cf.getChannel());
                    return cf;
                }
            }
        }
     
        final OnConnect onConnect = new OnConnect();
        
        // We synchronize to avoid creating duplicate connections to the
        // same host, which we shouldn't for a single connection from the
        // browser. Note the synchronization here is short-lived, however,
        // due to the asynchronous connection establishment.
        synchronized (endpointsToChannelFutures) {
            final ChannelFuture curFuture = 
                endpointsToChannelFutures.get(hostAndPort);
            if (curFuture != null) {
                
                if (curFuture.getChannel().isConnected()) {
                    onConnect.onConnect(curFuture);
                }
                else {
                    final ChannelFutureListener cfl = new ChannelFutureListener() {
                        public void operationComplete(final ChannelFuture future)
                            throws Exception {
                            onConnect.onConnect(curFuture);
                        }
                    };
                    curFuture.addListener(cfl);
                }
            }
            else {
                log.info("Establishing new connection");
                final ChannelFutureListener closedCfl = new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture closed) 
                        throws Exception {
                        endpointsToChannelFutures.remove(hostAndPort);
                    }
                };
                final ChannelFuture cf = 
                    newChannelFuture(httpRequestCopy, inboundChannel);
                endpointsToChannelFutures.put(hostAndPort, cf);
                cf.addListener(new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        final Channel channel = future.getChannel();
                        channelGroup.add(channel);
                        if (future.isSuccess()) {
                            log.info("Connected successfully to: {}", channel);
                            channel.getCloseFuture().addListener(closedCfl);
                            
                            log.info("Writing message on channel...");
                            final ChannelFuture wf = onConnect.onConnect(cf);
                            wf.addListener(new ChannelFutureListener() {
                                public void operationComplete(final ChannelFuture wcf)
                                    throws Exception {
                                    log.info("Finished write: "+wcf+ " to: "+
                                        httpRequestCopy.getMethod()+" "+
                                        httpRequestCopy.getUri());
                                }
                            });
                        }
                        else {
                            log.info("Could not connect to "+hostAndPort, 
                                future.getCause());
                            if (browserToProxyConnections == 1) {
                                log.warn("Closing browser to proxy channel " +
                                    "after not connecting to: {}", hostAndPort);
                                me.getChannel().close();
                                endpointsToChannelFutures.remove(hostAndPort);
                            }
                        }
                    }
                });
            }
        }
            
        if (request.isChunked()) {
            readingChunks = true;
        }
    }

    private void writeConnectResponse(final ChannelHandlerContext ctx, 
        final HttpRequest httpRequest, final Channel outgoingChannel) {
        final int port = ProxyUtils.parsePort(httpRequest);
        final Channel browserToProxyChannel = ctx.getChannel();
        
        // TODO: We should really only allow access on 443, but this breaks
        // what a lot of browsers do in practice.
        //if (port != 443) {
        if (port < 0) {
            log.warn("Connecting on port other than 443!!");
            final String statusLine = "HTTP/1.1 502 Proxy Error\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine, 
                ProxyUtils.PROXY_ERROR_HEADERS);
        }
        else {
            browserToProxyChannel.setReadable(false);
            
            // We need to modify both the pipeline encoders and decoders for the
            // browser to proxy channel *and* the encoders and decoders for the
            // proxy to external site channel.
            ctx.getPipeline().remove("encoder");
            ctx.getPipeline().remove("decoder");
            ctx.getPipeline().remove("handler");
            
            ctx.getPipeline().addLast("handler", 
                new HttpConnectRelayingHandler(outgoingChannel, this.channelGroup));
            
            final String statusLine = "HTTP/1.1 200 Connection established\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine, 
                ProxyUtils.CONNECT_OK_HEADERS);
            
            // TODO: Set this back to readable true?
            browserToProxyChannel.setReadable(true);
        }
    }

    private ChannelFuture newChannelFuture(final HttpRequest httpRequest, 
        final Channel browserToProxyChannel) {
        final String host;
        final int port;
        if (hostAndPort.contains(":")) {
            host = StringUtils.substringBefore(hostAndPort, ":");
            final String portString = 
                StringUtils.substringAfter(hostAndPort, ":");
            port = Integer.parseInt(portString);
        }
        else {
            host = hostAndPort;
            port = 80;
        }
        
        // Configure the client.
        final ClientBootstrap cb = new ClientBootstrap(clientChannelFactory);
        
        final ChannelPipelineFactory cpf;
        if (httpRequest.getMethod() == HttpMethod.CONNECT) {
            // In the case of CONNECT, we just want to relay all data in both 
            // directions. We SHOULD make sure this is traffic on a reasonable
            // port, however, such as 80 or 443, to reduce security risks.
            cpf = new ChannelPipelineFactory() {
                public ChannelPipeline getPipeline() throws Exception {
                    // Create a default pipeline implementation.
                    final ChannelPipeline pipeline = pipeline();
                    pipeline.addLast("handler", 
                        new HttpConnectRelayingHandler(browserToProxyChannel,
                            channelGroup));
                    return pipeline;
                }
            };
        }
        else {
            cpf = new ChannelPipelineFactory() {
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
                    pipeline.addLast("decoder", new HttpResponseDecoder());
                    
                    log.info("Querying for host and port: {}", hostAndPort);
                    final boolean shouldFilter;
                    final HttpFilter filter = filters.get(hostAndPort);
                    log.info("Using filter: {}", filter);
                    if (filter == null) {
                        log.info("Filter not found in: {}", filters);
                        shouldFilter = false;
                    }
                    else { 
                        shouldFilter = filter.shouldFilterResponses(httpRequest);
                    }
                    log.info("Filtering: "+shouldFilter);
                    
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
                            channelGroup, filter);
                    } else {
                        handler = new HttpRelayingHandler(browserToProxyChannel, 
                            channelGroup);
                    }
                    
                    final ProxyHttpRequestEncoder encoder = 
                        new ProxyHttpRequestEncoder(handler);
                    pipeline.addLast("encoder", encoder);
                    pipeline.addLast("handler", handler);
                    return pipeline;
                }
            };
        }
            
        // Set up the event pipeline factory.
        cb.setPipelineFactory(cpf);
        cb.setOption("connectTimeoutMillis", 40*1000);

        // Start the connection attempt.
        log.info("Starting new connection to: "+hostAndPort);
        final ChannelFuture future = 
            cb.connect(new InetSocketAddress(host, port));
        return future;
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel inboundChannel = cse.getChannel();
        log.info("New channel opened: {}", inboundChannel);
        totalBrowserToProxyConnections++;
        browserToProxyConnections++;
        log.info("Now "+totalBrowserToProxyConnections+
            " browser to proxy channels...");
        log.info("Now this class has "+browserToProxyConnections+
            " browser to proxy channels...");
        
        // We need to keep track of the channel so we can close it at the end.
        this.channelGroup.add(inboundChannel);
    }
    
    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) {
        log.warn("Channel closed: {}", cse.getChannel());
        totalBrowserToProxyConnections--;
        browserToProxyConnections--;
        log.info("Now "+totalBrowserToProxyConnections+
            " total browser to proxy channels...");
        log.info("Now this class has "+browserToProxyConnections+
            " browser to proxy channels...");
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (browserToProxyConnections == 0) {
            log.warn("Closing all proxy to web channels for this browser " +
                "to proxy connection!!!");
            final Collection<ChannelFuture> futures = 
                this.endpointsToChannelFutures.values();
            for (final ChannelFuture future : futures) {
                final Channel ch = future.getChannel();
                if (ch.isOpen()) {
                    future.getChannel().close();
                }
            }
        }
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        final Channel channel = e.getChannel();
        final Throwable cause = e.getCause();
        if (cause instanceof ClosedChannelException) {
            log.warn("Caught an exception on browser to proxy channel: "+
                channel, cause);
        }
        else {
            log.info("Caught an exception on browser to proxy channel: "+
                channel, cause);
        }
        if (channel.isOpen()) {
            closeOnFlush(channel);
        }
    }
    
    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private static void closeOnFlush(final Channel ch) {
        log.warn("Closing on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(
                ChannelFutureListener.CLOSE);
        }
    }
}
