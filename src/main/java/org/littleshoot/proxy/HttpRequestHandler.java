package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
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
import org.jboss.netty.handler.timeout.IdleState;
import org.jboss.netty.handler.timeout.IdleStateAwareChannelHandler;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for handling all HTTP requests from the browser to the proxy.
 * 
 * Note this class only ever handles a single connection from the browser.
 * The browser can and will, however, send requests to multiple hosts using
 * that same connection, i.e. it will send a request to host B once a request
 * to host A has completed.
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler 
    implements RelayListener, ConnectionData {

    private final static Logger log = 
        LoggerFactory.getLogger(HttpRequestHandler.class);
    protected static final Timer timer = new HashedWheelTimer();
    private volatile boolean readingChunks;
    
    private static volatile int totalBrowserToProxyConnections = 0;
    private volatile int browserToProxyConnections = 0;
    
    private final Map<String, ChannelFuture> endpointsToChannelFutures = 
        new ConcurrentHashMap<String, ChannelFuture>();
    
    private volatile int messagesReceived = 0;
    
    private volatile int numWebConnections = 0;
    private volatile int unansweredRequestCount = 0;
    
    private volatile int requestsSent = 0;
    
    private volatile int responsesReceived = 0;
    
    private final ProxyAuthorizationManager authorizationManager;
    
    private final Set<String> answeredRequests = new HashSet<String>();
    private final Set<String> unansweredRequests = new HashSet<String>();
    
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
    private final HttpRequestFilter requestFilter;
    
    private final AtomicBoolean browserChannelClosed = new AtomicBoolean(false);
    private volatile boolean receivedChannelClosed = false;
    private final boolean useJmx;
    
    /**
     * Creates a new class for handling HTTP requests with no frills.
     * 
     * @param clientChannelFactory The common channel factory for clients.
     */
    public HttpRequestHandler(
        final ClientSocketChannelFactory clientChannelFactory) {
        this(null, null, null, new HashMap<String, HttpFilter> (), 
            clientChannelFactory, null, null, false);
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
            clientChannelFactory, null, null, false);
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
     * @param chainProxyHostAndPort upstream proxy server host and port or null 
     * if none used.
     * @param requestFilter An optional filter for HTTP requests.
     * @param useJmx Whether or not to expose debugging properties via JMX.
     */
    public HttpRequestHandler(final ProxyCacheManager cacheManager, 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters,
        final ClientSocketChannelFactory clientChannelFactory,
        final String chainProxyHostAndPort, 
        final HttpRequestFilter requestFilter, final boolean useJmx) {
        this.cacheManager = cacheManager;
        this.authorizationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.filters = filters;
        this.clientChannelFactory = clientChannelFactory;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
        this.requestFilter = requestFilter;
        this.useJmx = useJmx;
        if (useJmx) {
            setupJmx();
        }
    }


    private void setupJmx() {
        final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            final Class<? extends SimpleChannelUpstreamHandler> clazz = 
                getClass();
            final String pack = clazz.getPackage().getName();
            final String oName =
                pack+":type="+clazz.getSimpleName()+"-"+clazz.getSimpleName() + 
                hashCode();
            log.info("Registering MBean with name: {}", oName);
            final ObjectName mxBeanName = new ObjectName(oName);
            if(!mbs.isRegistered(mxBeanName)) {
                mbs.registerMBean(this, mxBeanName);
            }
        } catch (final MalformedObjectNameException e) {
            log.error("Could not set up JMX", e);
        } catch (final InstanceAlreadyExistsException e) {
            log.error("Could not set up JMX", e);
        } catch (final MBeanRegistrationException e) {
            log.error("Could not set up JMX", e);
        } catch (final NotCompliantMBeanException e) {
            log.error("Could not set up JMX", e);
        }
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
        
        if (this.cacheManager != null &&
            this.cacheManager.returnCacheHit((HttpRequest)me.getMessage(), 
            me.getChannel())) {
            log.info("Found cache hit! Cache wrote the response.");
            return;
        }
        
        final HttpRequest request = (HttpRequest) me.getMessage();
        
        log.info("Got request: {} on channel: "+me.getChannel(), request);
        if (this.authorizationManager != null && 
            !this.authorizationManager.handleProxyAuthorization(request, ctx)) {
            log.info("Not authorized!!");
            return;
        }
        
        if (this.chainProxyHostAndPort != null) {
            this.hostAndPort = this.chainProxyHostAndPort;
        } else {
            this.hostAndPort = ProxyUtils.parseHostAndPort(request);
        }
        
        final Channel inboundChannel = me.getChannel();
        
        final class OnConnect {
            public ChannelFuture onConnect(final ChannelFuture cf) {
                if (request.getMethod() != HttpMethod.CONNECT) {
                    final ChannelFuture writeFuture = cf.getChannel().write(request);
                    writeFuture.addListener(new ChannelFutureListener() {
                        
                        public void operationComplete(final ChannelFuture future) 
                            throws Exception {
                            if (useJmx) {
                                unansweredRequests.add(request.toString());
                            }
                            unansweredRequestCount++;
                            requestsSent++;
                        }
                    });
                    return writeFuture;
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
                log.info("Using exising connection...");
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
                /*
                final ChannelFutureListener closedCfl = new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture closed) 
                        throws Exception {
                        endpointsToChannelFutures.remove(hostAndPort);
                    }
                };
                */
                final ChannelFuture cf = 
                    newChannelFuture(request, inboundChannel);
                endpointsToChannelFutures.put(hostAndPort, cf);
                cf.addListener(new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        final Channel channel = future.getChannel();
                        if (channelGroup != null) {
                            channelGroup.add(channel);
                        }
                        if (future.isSuccess()) {
                            log.info("Connected successfully to: {}", channel);
                            log.info("Writing message on channel...");
                            final ChannelFuture wf = onConnect.onConnect(cf);
                            wf.addListener(new ChannelFutureListener() {
                                public void operationComplete(final ChannelFuture wcf)
                                    throws Exception {
                                    log.info("Finished write: "+wcf+ " to: "+
                                        request.getMethod()+" "+
                                        request.getUri());
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
            
            browserToProxyChannel.setReadable(true);
        }
    }

    private ChannelFuture newChannelFuture(final HttpRequest httpRequest, 
        final Channel browserToProxyChannel) {
        this.numWebConnections++;
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
            cpf = newDefaultRelayPipeline(httpRequest, browserToProxyChannel);
        }
            
        cb.setPipelineFactory(cpf);
        cb.setOption("connectTimeoutMillis", 40*1000);
        log.info("Starting new connection to: {}", hostAndPort);
        final ChannelFuture future = 
            cb.connect(new InetSocketAddress(host, port));
        return future;
    }
    
    private ChannelPipelineFactory newDefaultRelayPipeline(
        final HttpRequest httpRequest, final Channel browserToProxyChannel) {
        return new ChannelPipelineFactory() {
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
                
                log.info("Querying for host and port: {}", hostAndPort);
                final boolean shouldFilter;
                final HttpFilter filter = filters.get(hostAndPort);
                if (filter == null) {
                    log.info("Filter not found in: {}", filters);
                    shouldFilter = false;
                }
                else {
                    log.info("Using filter: {}", filter);
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
                        channelGroup, filter, HttpRequestHandler.this, hostAndPort);
                } else {
                    handler = new HttpRelayingHandler(browserToProxyChannel, 
                        channelGroup, HttpRequestHandler.this, hostAndPort);
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
                    new IdleStateHandler(timer, readTimeoutSeconds, 
                        writeTimeoutSeconds, 0));
                pipeline.addLast("idleAware", new IdleAwareHandler());
                pipeline.addLast("handler", handler);
                return pipeline;
            }
        };
    }

    /**
     * This handles idle sockets.
     */
    public class IdleAwareHandler extends IdleStateAwareChannelHandler {

        @Override
        public void channelIdle(final ChannelHandlerContext ctx, 
            final IdleStateEvent e) {
            if (e.getState() == IdleState.READER_IDLE) {
                log.info("Got reader idle -- closing");
                e.getChannel().close();
            } else if (e.getState() == IdleState.WRITER_IDLE) {
                log.info("Got writer idle -- closing connection");
                e.getChannel().close();
            }
        }
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
        if (this.channelGroup != null) {
            this.channelGroup.add(inboundChannel);
        }
    }
    
    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) {
        log.info("Channel closed: {}", cse.getChannel());
        totalBrowserToProxyConnections--;
        browserToProxyConnections--;
        log.info("Now "+totalBrowserToProxyConnections+
            " total browser to proxy channels...");
        log.info("Now this class has "+browserToProxyConnections+
            " browser to proxy channels...");
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (browserToProxyConnections == 0) {
            log.info("Closing all proxy to web channels for this browser " +
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
    
    public void onRelayChannelClose(final Channel browserToProxyChannel, 
        final String key) {
        this.receivedChannelClosed = true;
        this.numWebConnections--;
        if (this.numWebConnections == 0 || this.unansweredRequestCount == 0) {
            if (!browserChannelClosed.getAndSet(true)) {
                log.info("Closing browser to proxy channel");
                ProxyUtils.closeOnFlush(browserToProxyChannel);
            }
        }
        else {
            log.info("Not closing browser to proxy channel. Still "+
                this.numWebConnections+" connections and awaiting "+
                this.unansweredRequestCount + " responses");
        }
        this.endpointsToChannelFutures.remove(key);
        
        if (numWebConnections != this.endpointsToChannelFutures.size()) {
            log.error("Something's amiss. We have "+numWebConnections+" and "+
                this.endpointsToChannelFutures.size()+" connections stored");
        }
        else {
            log.info("WEB CONNECTIONS COUNTS IN SYNC..REMAINING "+
                this.numWebConnections);
        }
    }
    

    public void onRelayHttpResponse(final Channel browserToProxyChannel,
        final String key, final HttpRequest httpRequest) {
        if (this.useJmx) {
            this.answeredRequests.add(httpRequest.toString());
            this.unansweredRequests.remove(httpRequest.toString());
        }
        this.unansweredRequestCount--;
        this.responsesReceived++;
        // If we've received responses to all outstanding requests and one
        // of those outgoing channels has been closed, we should close the
        // connection to the browser.
        if (this.unansweredRequestCount == 0 && this.receivedChannelClosed) {
            if (!browserChannelClosed.getAndSet(true)) {
                log.info("Closing browser to proxy channel on HTTP response");
                ProxyUtils.closeOnFlush(browserToProxyChannel);
            }
        }
        else {
            log.info("Not closing browser to proxy channel. Still "+
                "awaiting " + this.unansweredRequestCount+" responses..." +
                "receivedChannelClosed="+this.receivedChannelClosed);
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
        ProxyUtils.closeOnFlush(channel);
    }

    public int getClientConnections() {
        return this.browserToProxyConnections;
    }
    
    public int getTotalClientConnections() {
        return totalBrowserToProxyConnections;
    }

    public int getOutgoingConnections() {
        return numWebConnections;
    }

    public int getRequestsSent() {
        return this.requestsSent;
    }

    public int getResponsesReceived() {
        return this.responsesReceived;
    }

    public String getUnansweredRequests() {
        return this.unansweredRequests.toString();
    }

    public String getAnsweredReqeusts() {
        return this.answeredRequests.toString();
    }
}
