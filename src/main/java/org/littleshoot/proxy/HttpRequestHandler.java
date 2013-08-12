package org.littleshoot.proxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.net.ssl.SSLEngine;

import org.apache.commons.lang3.StringUtils;
import org.littleshoot.dnssec4j.VerifiedAddressFactory;
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
public class HttpRequestHandler extends SimpleChannelInboundHandler<HttpObject> 
    implements RelayListener, ConnectionData {

    private final static Logger log = 
        LoggerFactory.getLogger(HttpRequestHandler.class);
    
    private volatile boolean readingChunks;
    
    private static final AtomicInteger totalBrowserToProxyConnections = 
        new AtomicInteger(0);
    private final AtomicInteger browserToProxyConnections = 
        new AtomicInteger(0);
    
    private final Map<String, Queue<ChannelFuture>> externalHostsToChannelFutures = 
        new ConcurrentHashMap<String, Queue<ChannelFuture>>();
    
    private final AtomicInteger messagesReceived = 
        new AtomicInteger(0);
    
    private final AtomicInteger unansweredRequestCount = 
        new AtomicInteger(0);
    
    private final AtomicInteger requestsSent = 
        new AtomicInteger(0);
    
    private final AtomicInteger responsesReceived = 
        new AtomicInteger(0);
    
    private final ProxyAuthorizationManager authorizationManager;
    
    private final Set<String> answeredRequests = new HashSet<String>();
    private final Set<String> unansweredRequests = new HashSet<String>();

    private final Set<HttpRequest> unansweredHttpRequests = 
        new HashSet<HttpRequest>();

    private ChannelFuture currentChannelFuture;
    
    /**
     * We need to keep track of all external channels we've created so we
     * can close them if/when the client connection closes. This is 
     * particularly important for things like long-lived connections, where
     * the relaying handler won't notify us the channel is available until
     * after the full response is written, which never happens if a large
     * response is cancelled en-route.
     */
    private final Set<ChannelFuture> allChannelFutures = 
            Collections.synchronizedSet(new HashSet<ChannelFuture>());
    
    /**
     * This lock is necessary for when a second chunk arrives in a request
     * before we've even created the current channel future.
     */
    private final Object channelFutureLock = new Object();
    
    private final ChainProxyManager chainProxyManager;
    private final ChannelGroup channelGroup;

    private final AtomicBoolean browserChannelClosed = new AtomicBoolean(false);
    private volatile boolean receivedChannelClosed = false;
    
    private final RelayChannelInitializerFactory relayChannelInitializerFactory;
    private EventLoopGroup clientWorker;
    
    /**
     * This flag is necessary for edge cases where we prematurely halt request
     * processing but where there may be more incoming chunks for the request
     * (in cases where the request is chunked). This happens, for example, with
     * proxy authentication and chunked posts or when the external host just
     * does not resolve to an IP address. In those cases we prematurely return
     * pre-packaged responses and halt request processing but still need to 
     * handle any future chunks associated with the request coming in on the
     * client channel.
     */
    private boolean pendingRequestChunks = false;
    private ObjectName mxBeanName;
    
    /**
     * The collection of all {@link InterestOpsListener}s for this persistent 
     * incoming channel (which can generate multiple outgoing channels for
     * different requests over the persistent connection);
     */
    private final Set<InterestOpsListener> interestOpsListeners = 
        Collections.synchronizedSet(new HashSet<InterestOpsListener>());
    
    /**
     * Creates a new class for handling HTTP requests with no frills.
     * 
     * @param relayChannelInitializerFactory The factory for creating initializers
     * for channels to relay data from external sites back to clients.
     * @param clientWorker
     * The EventLoopGroup for creating outgoing channels to external sites.
     */
    public HttpRequestHandler(
        final RelayChannelInitializerFactory relayChannelInitializerFactory,
        final EventLoopGroup clientWorker) {
        this(null, null, null, 
                relayChannelInitializerFactory, clientWorker);
    }
    
    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * 
     * @param authorizationManager The class that handles any 
     * proxy authentication requirements.
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param relayChannelInitializerFactory The factory for creating initializers
     * for channels to relay data from external sites back to clients.
     * @param clientWorker
     * The EventLoopGroup for creating outgoing channels to external sites.
     */
    public HttpRequestHandler( 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final RelayChannelInitializerFactory relayChannelInitializerFactory,
        final EventLoopGroup clientWorker) {
        this(authorizationManager, channelGroup,
            null, relayChannelInitializerFactory, clientWorker);
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
     * @param chainProxyManager upstream proxy server host and port or null 
     * if none used.
     * @param relayChannelInitializerFactory The factory for creating initializers
     * for channels to relay data from external sites back to clients.
     * @param clientWorker
     * The EventLoopGroup for creating outgoing channels to external sites.
     */
    public HttpRequestHandler( 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ChainProxyManager chainProxyManager, 
        final RelayChannelInitializerFactory relayChannelInitializerFactory,
        final EventLoopGroup clientWorker) {
        log.info("Creating new request handler...");
        this.clientWorker = clientWorker;
        this.authorizationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.chainProxyManager = chainProxyManager;
        this.relayChannelInitializerFactory = relayChannelInitializerFactory;
        if (LittleProxyConfig.isUseJmx()) {
            setupJmx();
        }
    }

    private void setupJmx() {
        final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            final Class<? extends SimpleChannelInboundHandler> clazz = 
                getClass();
            final String pack = clazz.getPackage().getName();
            final String oName =
                pack+":type="+clazz.getSimpleName()+"-"+clazz.getSimpleName() + 
                "-"+hashCode();
            log.info("Registering MBean with name: {}", oName);
            mxBeanName = new ObjectName(oName);
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

    protected void cleanupJmx() {
        if (this.mxBeanName == null) {
            log.debug("JMX not setup");
            return;
        }
            
        final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
          mbs.unregisterMBean(mxBeanName);
        } catch (final MBeanRegistrationException e) {
            //that's OK, because we won't leak
        } catch (final InstanceNotFoundException e) {
            //ditto
        }
    }

    @Override
    protected void channelRead0(final ChannelHandlerContext ctx,  final HttpObject httpObject ) {
        if (browserChannelClosed.get()) {
            log.info("Ignoring message since the connection to the browser " +
                "is about to close");
            return;
        }
        messagesReceived.incrementAndGet();
        log.debug("Received "+messagesReceived+" total messages");
        if (!readingChunks) {
            processRequest(ctx, httpObject);
        } 
        else {
            processChunk(httpObject);
        }
    }

    private void processChunk(final HttpObject httpObject) {
        log.info("Processing chunk...");
        final HttpContent chunk = (HttpContent) httpObject;
        boolean isLastChunk = ProxyUtils.isLastChunk(chunk);
        
        // Remember this will typically be a persistent connection, so we'll
        // get another request after we're read the last chunk. So we need to
        // reset it back to no longer read in chunk mode.
        if (isLastChunk) {
            this.readingChunks = false;
        }
        
        // It's possible to receive a chunk before a channel future has even
        // been set. It's also possible for this to happen for requests that
        // require proxy authentication or in cases where we get a DNS lookup
        // error trying to reach the remote site.
        if (this.currentChannelFuture == null) {
            // First deal with the case where a proxy authentication manager
            // is active and we've received an HTTP POST requiring 
            // authentication. In that scenario, we've returned a 
            // 407 Proxy Authentication Required response, but we still need
            // to handle any incoming chunks from the original request. We
            // basically just drop them on the floor because the client will
            // issue a new POST request with the appropriate credentials 
            // (assuming they have them) and associated chunks in the new 
            // request body.
            if (pendingRequestChunks) {
                if (isLastChunk) {
                    log.info("Received last chunk -- setting proxy auth " +
                        "chunking to false");
                    this.pendingRequestChunks = false;
                    
                    //me.channel().close();
                }
                log.info("Ignoring chunk with chunked post for edge case");
                return;
            } else {
                // Note this can happen quite often in tests when requests are
                // arriving very quickly on the same JVM but is less likely
                // to occur in deployed servers.
                log.warn("NO CHANNEL FUTURE!!");
                synchronized (this.channelFutureLock) {
                    if (this.currentChannelFuture == null) {
                        log.debug("Waiting for channel future!");
                        try {
                            this.channelFutureLock.wait(30000);
                        } catch (InterruptedException ie) {
                            log.warn("Interrupted!");
                        }
                        log.debug("Got channel future? " + (this.currentChannelFuture != null));
                    }
                }
            }
        }
        
        // We don't necessarily know the channel is connected yet!! This can
        // happen if the client sends a chunk directly after the initial 
        // request.
        
        // Retain the content for this chunk before passing it to the outbound
        // channel
        chunk.content().retain();
        if (this.currentChannelFuture.channel().isActive()) {
            this.currentChannelFuture.channel().writeAndFlush(chunk);
        }
        else {
            this.currentChannelFuture.addListener(new ChannelFutureListener() {
                
                @Override
                public void operationComplete(final ChannelFuture future) 
                    throws Exception {
                    log.debug("currentChannelFuture now active, writing chunk");
                    currentChannelFuture.channel().writeAndFlush(chunk);
                }
            });
        }
    }
    
    private void processRequest(final ChannelHandlerContext ctx, 
        final HttpObject httpObject) {
        
        final HttpRequest request = (HttpRequest) httpObject;
        
        final Channel browserToProxyChannel = ctx.channel();
        this.unansweredRequestCount.incrementAndGet();
        
        log.debug("Got request: {} on channel: "+browserToProxyChannel, request);
        if (this.authorizationManager != null && 
            !this.authorizationManager.handleProxyAuthorization(request, ctx)) {
            log.debug("Not authorized!!");
            // We need to do a few things here. First, if the request is 
            // chunked, we need to make sure we read the full request/POST
            // message body.
            handleFutureChunksIfNecessary(request);
            return;
        } else {
            this.pendingRequestChunks = false;
        }
        
        String hostAndPort = null;
        if (this.chainProxyManager != null) {
            hostAndPort = this.chainProxyManager.getChainProxy(request);
        }
        
        if (hostAndPort == null) {
            hostAndPort = ProxyUtils.parseHostAndPort(request);
            if (StringUtils.isBlank(hostAndPort)) {
                final List<String> hosts = 
                    request.headers().getAll(HttpHeaders.Names.HOST);
                if (hosts != null && !hosts.isEmpty()) {
                    hostAndPort = hosts.get(0);
                } else {
                    log.warn("No host and port found in {}", request.getUri());
                    badGateway(request, browserToProxyChannel);
                    handleFutureChunksIfNecessary(request);
                    return;
                }
                
            }
        }
        
        final class OnConnect {
            public ChannelFuture onConnect(final String hostAndPort,
                    final ChannelFuture cf) {
                if (request.getMethod() != HttpMethod.CONNECT) {
                    if (request instanceof HttpContent) {
                        // Retain the content for this request before passing it
                        // to the outbound channel
                        ((HttpContent) request).content().retain();
                    }
                    final ChannelFuture writeFuture = 
                        cf.channel().writeAndFlush(request);
                    writeFuture.addListener(new ChannelFutureListener() {
                        
                        @Override
                        public void operationComplete(final ChannelFuture future) 
                            throws Exception {
                            if (LittleProxyConfig.isUseJmx()) {
                                unansweredRequests.add(request.toString());
                            }
                            unansweredHttpRequests.add(request);
                            requestsSent.incrementAndGet();
                        }
                    });
                    return writeFuture;
                }
                else {
                	if (LittleProxyConfig.isUseSSLMitm()) {
	                    //TODO:nir: verify connect ok??
	                    //TODO:nir: will this still work in case of proxy chaining?
	                    final ChannelPipeline pipeline = cf.channel().pipeline();
	                    log.info("Adding proxy to web SSL handler");
	                    final SslContextFactory scf = new SslContextFactory(new SelfSignedKeyStoreManager());
	                    final SSLEngine engine = scf.getClientContext().createSSLEngine();
	                    engine.setUseClientMode(true);
	                    final SslHandler handler = new SslHandler(engine);
	                    pipeline.addFirst("ssl", handler);
	                    log.info("Running proxy to web SSL handshake");
	                    final Future<Channel> handshakeFuture = handler.handshakeFuture();
	                    handshakeFuture.addListener(new GenericFutureListener<Future<? super Channel>>() {
                                    @Override
                                    public void operationComplete(
                                            Future<? super Channel> future)
                                            throws Exception {
                                        log.info("Proxy to web SSL handshake done. Success is: "
                                                + future.isSuccess());

                                        // Mark this ChannelFuture as available
                                        onChannelAvailable(hostAndPort, cf);
                                        
                                        // signaling on the client channel that
                                        // we have connected to the server
                                        // successfully
                                        writeConnectResponse(ctx, request,
                                                (Channel) future.get(),
                                                future.isSuccess());
                                    }
                        });
                	}
                	else {
                	    // Mark this ChannelFuture as available
                        onChannelAvailable(hostAndPort, cf);
                        
                		writeConnectResponse(ctx, request, cf.channel(), true);
                	}
                	return cf;
                }
            }
        }
     
        final OnConnect onConnect = new OnConnect();
        
        final ChannelFuture curFuture = channelFuture(hostAndPort);
        if (curFuture != null) {
            log.debug("Using existing connection...");
            
            // We don't notify here because the current channel future will not
            // have been null before this assignment.
            if (this.currentChannelFuture == null) {
                log.error("Should not be null here");
            }
            this.currentChannelFuture = curFuture;
            if (curFuture.channel().isActive()) {
                onConnect.onConnect(hostAndPort, curFuture);
            }
            else {
                final String finalHostAndPort = hostAndPort;
                final ChannelFutureListener cfl = new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        onConnect.onConnect(finalHostAndPort, curFuture);
                    }
                };
                curFuture.addListener(cfl);
            }
        }
        else {
            log.debug("Establishing new connection");
            final ChannelFuture cf;
            browserToProxyChannel.config().setAutoRead(false);
            try {
                cf = newChannelFuture(request, browserToProxyChannel, hostAndPort);
            } catch (final UnknownHostException e) {
                log.warn("Could not resolve host?", e);
                badGateway(request, browserToProxyChannel);
                handleFutureChunksIfNecessary(request);
                browserToProxyChannel.config().setAutoRead(true);
                return;
            }
            
            final class LocalChannelFutureListener implements ChannelFutureListener {
                
                private final String copiedHostAndPort;

                LocalChannelFutureListener(final String copiedHostAndPort) {
                    this.copiedHostAndPort = copiedHostAndPort;
                }
            
                @Override
                public void operationComplete(final ChannelFuture future)
                    throws Exception {
                    final Channel channel = future.channel();
                    if (channelGroup != null) {
                        channelGroup.add(channel);
                    }
                    if (future.isSuccess()) {
                        log.debug("Connected successfully to: {}", channel);
                        log.debug("Firing onConnect...");
                        final ChannelFuture wf = onConnect.onConnect(copiedHostAndPort, cf);
                        log.debug("Writing message on channel...");
                        wf.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(final ChannelFuture wcf)
                                throws Exception {
                                log.debug("Finished write: "+wcf+ " to: "+
                                    request.getMethod()+" "+
                                    request.getUri());
                                
                                browserToProxyChannel.config().setAutoRead(true);
                                log.debug("Channel is auto reading: {}", 
                                    channel.config().isAutoRead());
                            }
                        });
                        currentChannelFuture = wf;
                        synchronized(channelFutureLock) {
                            channelFutureLock.notifyAll();
                        }
                    }
                    else {
                        log.debug("Could not connect to " + copiedHostAndPort, 
                            future.cause());
                        
                        final String nextHostAndPort;
                        if (chainProxyManager == null) {
                            nextHostAndPort = copiedHostAndPort;
                        }
                        else {
                            chainProxyManager.onCommunicationError(copiedHostAndPort);
                            nextHostAndPort = chainProxyManager.getChainProxy(request);
                        }
                        
                        if (copiedHostAndPort.equals(nextHostAndPort)) {
                            // We call the relay channel closed event handler
                            // with one associated unanswered request.
                            onRelayChannelClose(browserToProxyChannel, copiedHostAndPort, 1,
                                true);
                        }
                        else {
                            // TODO I am not sure about this
                            removeProxyToWebConnection(copiedHostAndPort);
                            // try again with different hostAndPort
                            processRequest(ctx, httpObject);
                        }
                    }
                    if (LittleProxyConfig.isUseJmx()) {
                        cleanupJmx();
                    }
                }
            }
            
            cf.addListener(new LocalChannelFutureListener(hostAndPort));
        }
            
        if (ProxyUtils.isChunked(request)) {
            readingChunks = true;
        }
    }
    
    
    private void badGateway(final HttpRequest request, 
        final Channel inboundChannel) {
        final String body = "Bad Gateway: "+request.getUri();
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        ByteBuf content = Unpooled.copiedBuffer(bytes);
        final DefaultFullHttpResponse response = 
            new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, 
                HttpResponseStatus.BAD_GATEWAY,
                content);
        response.headers().set(HttpHeaders.Names.CONNECTION, "close");
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, bytes.length);
        inboundChannel.writeAndFlush(response);
    }

    private void handleFutureChunksIfNecessary(final HttpRequest request) {
        if (ProxyUtils.isChunked(request)) {
            this.pendingRequestChunks = true;
            readingChunks = true;
        }
    }

    @Override
    public void onChannelAvailable(final String hostAndPortKey, 
        final ChannelFuture cf) {
        synchronized (this.externalHostsToChannelFutures) {
            final Queue<ChannelFuture> futures = 
                this.externalHostsToChannelFutures.get(hostAndPortKey);
            
            final Queue<ChannelFuture> toUse;
            if (futures == null) {
                toUse = new LinkedList<ChannelFuture>();
                this.externalHostsToChannelFutures.put(hostAndPortKey, toUse);
            } else {
                toUse = futures;
            }
            toUse.add(cf);
        }
    }

    private ChannelFuture channelFuture(final String hostAndPort) {
        if(StringUtils.isBlank(hostAndPort)) {
            return currentChannelFuture;
        }
        
        synchronized (this.externalHostsToChannelFutures) {
            final Queue<ChannelFuture> futures = 
                this.externalHostsToChannelFutures.get(hostAndPort);
            if (futures == null) {
                return null;
            }
            if (futures.isEmpty()) {
                return null;
            }
            final ChannelFuture cf = futures.remove();

            if (cf != null && cf.isDone() && !cf.channel().isActive()) {
                // In this case, the future tried to connect at one time,
                // but we're no longer connected. We need to remove the channel
                // and open a new one.
                removeProxyToWebConnection(hostAndPort);
                return null;
            }
            return cf;
        }
    }

    private void writeConnectResponse(final ChannelHandlerContext ctx,
            final HttpRequest httpRequest, final Channel outgoingChannel,
            final boolean didHandshakeSucceed) {
        ctx.channel().config().setAutoRead(false);
        // As of Netty 4, the implementation of DefaultChannelPipeline.remove()
        // is not safe to call from within the event loop because it may itself
        // try to schedule something on the event loop and then block waiting
        // for that task to complete.  This causes a deadlock.
        // As a workaround, we do our actual work as a task on the event loop.
        // By the time pipeline.remove() is called, DefaultChannelPipeline will
        // no longer need to schedule a task on the event loop and we avoid
        // deadlock.
        ctx.executor().submit(new Runnable() {
            @Override
            public void run() {
                doWriteConnectResponse(ctx,
                        httpRequest,
                        outgoingChannel,
                        didHandshakeSucceed);
            }
        });
    }
    
    private void doWriteConnectResponse(final ChannelHandlerContext ctx,
        final HttpRequest httpRequest, final Channel outgoingChannel, boolean didHandshakeSucceed) {
        log.debug("Writing connect response: {}", httpRequest);
        ctx.channel().config().setAutoRead(true);
        final int port = ProxyUtils.parsePort(httpRequest);
        final Channel browserToProxyChannel = ctx.channel();
        
        // TODO: We should really only allow access on 443, but this breaks
        //TODO:nir: we should intercept such requests when they arrive and before we have created the browser to web channel
        // what a lot of browsers do in practice.
        if (port != 443) {
            log.warn("Connecting on port other than 443: "+httpRequest.getUri());
        }
        if (port < 0) {
            log.warn("Connecting on port other than 443!!");
            final String statusLine = "HTTP/1.1 502 Proxy Error\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine, 
                ProxyUtils.PROXY_ERROR_HEADERS);
            ProxyUtils.closeOnFlush(browserToProxyChannel);
        }
        else if (!LittleProxyConfig.isUseSSLMitm()) {
            log.debug("Modifying handlers for tunneling");
            ctx.channel().config().setAutoRead(false);
            
            // We need to modify both the pipeline encoders and decoders for
            // the
            // browser to proxy channel -- the outgoing channel already has
            // the correct handlers and such set at this point.
            ChannelPipeline pipeline = browserToProxyChannel.pipeline();
            pipeline.remove("encoder");
            pipeline.remove("decoder");
            pipeline.remove("handler");
            
            // Note there are two HttpConnectRelayingHandler for each HTTP
            // CONNECT tunnel -- one writing to the browser, and one writing
            // to the remote host.
            pipeline.addLast(
                    "handler",
                    new HttpConnectRelayingHandler(outgoingChannel,
                            this.channelGroup));
        }

        log.debug("Sending response to CONNECT request...");
        
        // This is sneaky -- thanks to Emil Goicovici from the list --
        // We temporarily add in a request encoder if we're chaining, allowing
        // us to forward along the HTTP CONNECT request. We then remove that
        // encoder as soon as it's written since past that point we simply
        // want to relay all data.
        String chainProxy = null;
        if (chainProxyManager != null) {
            chainProxy = chainProxyManager.getChainProxy(httpRequest);
            //TODO:nir: SSL intercept support: it seems that we need to add SSLHandler in this case after we receive
            //TODO:nir: the response from the chained proxy. Currently I'm not sure were to put that.
            if (chainProxy != null) {
                log.debug("Temporarily inserting HttpRequestEncoder to deal with CONNECT to upstream proxy");
                // forward the CONNECT request to the upstream proxy server 
                // which will return a HTTP response
                outgoingChannel.pipeline().addBefore("handler", "encoder",
                    new HttpRequestEncoder());
                outgoingChannel.writeAndFlush(httpRequest).addListener(
                    new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        outgoingChannel.pipeline().remove("encoder");
                    }
                });
            }
        }
        
        if (chainProxy == null) { 
        	if (didHandshakeSucceed) {
        	    log.debug("Connection established");
	            final String statusLine = "HTTP/1.1 200 Connection established\r\n";
	            //TODO:nir: why does writeResponse() calls config().setAutoRead(true)?
	            final ChannelFuture channelFuture = ProxyUtils.writeResponse(browserToProxyChannel, statusLine,
	                ProxyUtils.CONNECT_OK_HEADERS);
	            
	            if (LittleProxyConfig.isUseSSLMitm()) {
	                log.debug("Connecting with SSL MITM");
		            channelFuture.addListener(new ChannelFutureListener() {
		                public void operationComplete(ChannelFuture future) throws Exception {
		                    // don't accept incoming message until we'll setup the SSL handler

		                	// Uncomment to use a Signed Certificate manager and not the SelfSigned one. 
	//	                    future.channel().config().setAutoRead(false);
	//	                    log.info("Adding browser to proxy SSL handler");
	//	                    SignedKeyStoreManager manager = new SignedKeyStoreManager();
	//	                    manager.createKeyForDomain(httpRequest.getUri());
	//	                    final SignedSslContextFactory scf = new SignedSslContextFactory(manager);
	//	                    final SSLEngine engine = scf.getServerContext().createSSLEngine();
	//	                    engine.setUseClientMode(false);
	//	                    SslHandler handler = new SslHandler(engine);
	//	                    handler.setEnableRenegotiation(true);
	//	                    handler.setIssueHandshake(true);
	//	                    future.channel().pipeline().addFirst("ssl", handler);
	//	                    future.channel().config().setAutoRead(true);
	//	                    handler.handshake().addListener(new ChannelFutureListener() {
	//							@Override
	//							public void operationComplete(ChannelFuture future) throws Exception {
	//								log.info("Browser to proxy SSL handshake done. Success is: " + future.isSuccess());
	//							}
	//						});
		                    
		                    
		                    future.channel().config().setAutoRead(false);
		                    log.info("Adding browser to proxy SSL handler");
		                    final SslContextFactory scf = new SslContextFactory(new SelfSignedKeyStoreManager());
		                    final SSLEngine engine = scf.getServerContext().createSSLEngine();
		                    engine.setUseClientMode(false);
		                    SslHandler handler = new SslHandler(engine);
		                    future.channel().pipeline().addFirst("ssl", handler);
		                    future.channel().config().setAutoRead(true);
		                    handler.handshakeFuture().addListener(new GenericFutureListener<Future<? super Channel>>() {
		                        @Override
		                        public void operationComplete(
		                                Future<? super Channel> future)
		                                throws Exception {
		                            log.info("Browser to proxy SSL handshake done. Success is: {}", future.isSuccess());
		                        }
                            });
		                }
		            });
	            }
	            else {
	            	browserToProxyChannel.config().setAutoRead(true);
	            }
        	}
        	else {
        	    log.debug("Unable to establish connection");
        		// Send an error to the browser and close the connection. For our implementation, this is enough. If you 
        		// want the browser to give the correct error, you'd have to start the handshake with the browser and purposefully
        		// make the handshake fail be using an invalid certificate.        		
        		String statusLine = "HTTP/1.1 401 Unauthorized\r\n";
	            ChannelFuture channelFuture = ProxyUtils.writeResponse(browserToProxyChannel, statusLine,
	                ProxyUtils.PROXY_ERROR_HEADERS);
	            ProxyUtils.closeOnFlush(browserToProxyChannel);
				ProxyUtils.closeOnFlush(outgoingChannel);
        	}
        }
    }

    private ChannelFuture newChannelFuture(final HttpRequest httpRequest, 
        final Channel browserToProxyChannel, final String hostAndPort) 
        throws UnknownHostException {
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
        final Bootstrap cb = 
            new Bootstrap().group(this.clientWorker);

        final ChannelInitializer<Channel> channelInitializer;
        if (httpRequest.getMethod() == HttpMethod.CONNECT && !LittleProxyConfig.isUseSSLMitm()) {
            // In the case of CONNECT, we just want to relay all data in both 
            // directions. We SHOULD make sure this is traffic on a reasonable
            // port, however, such as 80 or 443, to reduce security risks.
            channelInitializer = new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ch.pipeline().addLast("handler", 
                        new HttpConnectRelayingHandler(browserToProxyChannel,
                            channelGroup));
                }
            };
        }
        else {
            channelInitializer = relayChannelInitializerFactory.getRelayChannelInitializer(
                httpRequest, browserToProxyChannel, this);
        }
        
        cb.channel(NioSocketChannel.class);
        cb.handler(channelInitializer);
        cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 40*1000);
        log.debug("Starting new connection to: {}", hostAndPort);
        final ChannelFuture cf;
        if (LittleProxyConfig.isUseDnsSec()) {
            cf = cb.connect(VerifiedAddressFactory.newInetSocketAddress(host, port, 
                LittleProxyConfig.isUseDnsSec()));
            
        } else {
            final InetAddress ia = InetAddress.getByName(host);
            final String address = ia.getHostAddress();
            cf = cb.connect(new InetSocketAddress(address, port));
        }
        allChannelFutures.add(cf);
        return cf;
    }
    
    
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        if (ctx.channel().isWritable()) {
            synchronized (interestOpsListeners) {
                for (final InterestOpsListener iol : interestOpsListeners) {
                    iol.channelWritable(ctx);
                }
            }
        }
    }
    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        super.channelRegistered(ctx);
        final Channel inboundChannel = ctx.channel();
        log.debug("New channel opened: {}", inboundChannel);
        totalBrowserToProxyConnections.incrementAndGet();
        browserToProxyConnections.incrementAndGet();
        log.debug("Now "+totalBrowserToProxyConnections+
            " browser to proxy channels...");
        log.debug("Now this class has "+browserToProxyConnections+
            " browser to proxy channels...");
        
        // We need to keep track of the channel so we can close it at the end.
        if (this.channelGroup != null) {
            this.channelGroup.add(inboundChannel);
        }
    }
    
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        super.channelUnregistered(ctx);
        log.debug("Channel closed: {}", ctx.channel());
        this.receivedChannelClosed = true;
        totalBrowserToProxyConnections.decrementAndGet();
        browserToProxyConnections.decrementAndGet();
        log.debug("Now "+totalBrowserToProxyConnections+
            " total browser to proxy channels...");
        log.debug("Now this class has "+browserToProxyConnections+
            " browser to proxy channels...");
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (browserToProxyConnections.get() == 0) {
            log.debug("Closing all proxy to web channels for this browser " +
                "to proxy connection!!!");
            this.externalHostsToChannelFutures.clear();
            synchronized (allChannelFutures) {
                for (final ChannelFuture cf : allChannelFutures) {
                    log.debug("Closing future...");
                    cf.channel().close();
                }
                allChannelFutures.clear();
            }
        }
    }
    
    /**
     * This is called when a relay channel to a remote server is closed in order
     * for this class to perform any necessary cleanup. Note that this is 
     * called on the same thread as the incoming request processing.
     */
    @Override
    public void onRelayChannelClose(final Channel browserToProxyChannel, 
        final String key, final int unansweredRequestsOnChannel,
        final boolean closedEndsResponseBody) {
        if (closedEndsResponseBody) {
            log.debug("Close ends response body");
            this.receivedChannelClosed = true;
        }
        log.debug("this.receivedChannelClosed: "+this.receivedChannelClosed);
        
        removeProxyToWebConnection(key);
        
        // The closed channel may have had outstanding requests we haven't 
        // properly accounted for. The channel closing effectively marks those
        // requests as "answered" when the responses didn't contain any other
        // markers for complete responses, such as Content-Length or the the
        // last chunk of a chunked encoding. All of this potentially results 
        // in the closing of the client/browser connection here.
        this.unansweredRequestCount.set(
            this.unansweredRequestCount.get() - unansweredRequestsOnChannel);
        //this.unansweredRequestCount -= unansweredRequestsOnChannel;
        if (this.receivedChannelClosed && 
            (this.externalHostsToChannelFutures.isEmpty() || 
             this.unansweredRequestCount.get() == 0)) {
            if (!browserChannelClosed.getAndSet(true)) {
                log.debug("Closing browser to proxy channel");
                ProxyUtils.closeOnFlush(browserToProxyChannel);
            }
        }
        else {
            log.debug("Not closing browser to proxy channel. Received channel " +
                "closed is "+this.receivedChannelClosed+" and we have {} " +
                "connections and awaiting {} responses", 
                this.externalHostsToChannelFutures.size(), 
                this.unansweredRequestCount );
        }
    }
    

    private void removeProxyToWebConnection(final String key) {
        // It's probably already been removed at this point, but just in case.
        this.externalHostsToChannelFutures.remove(key);
    }

    @Override
    public void onRelayHttpResponse(final Channel browserToProxyChannel,
        final String key, final HttpRequest httpRequest) {
        if (LittleProxyConfig.isUseJmx()) {
            this.answeredRequests.add(httpRequest.toString());
            this.unansweredRequests.remove(httpRequest.toString());
        }
        this.unansweredHttpRequests.remove(httpRequest);
        this.unansweredRequestCount.decrementAndGet();
        this.responsesReceived.incrementAndGet();
        // If we've received responses to all outstanding requests and one
        // of those outgoing channels has been closed, we should close the
        // connection to the browser.
        if (this.unansweredRequestCount.get() == 0 && this.receivedChannelClosed) {
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
        final Throwable cause) throws Exception {
        final Channel channel = ctx.channel();
        if (cause instanceof ClosedChannelException) {
            log.error("Caught an exception on browser to proxy channel: "+
                channel, cause);
        }
        else {
            log.warn("Caught an exception on browser to proxy channel: "+
                channel, cause);
        }
        ProxyUtils.closeOnFlush(channel);
    }

    @Override
    public int getClientConnections() {
        return this.browserToProxyConnections.get();
    }
    
    @Override
    public int getTotalClientConnections() {
        return totalBrowserToProxyConnections.get();
    }

    @Override
    public int getOutgoingConnections() {
        return this.externalHostsToChannelFutures.size();
    }

    @Override
    public int getRequestsSent() {
        return this.requestsSent.get();
    }

    @Override
    public int getResponsesReceived() {
        return this.responsesReceived.get();
    }

    @Override
    public String getUnansweredRequests() {
        return this.unansweredRequests.toString();
    }

    public Set<HttpRequest> getUnansweredHttpRequests() {
      return unansweredHttpRequests;
    }

    @Override
    public String getAnsweredReqeusts() {
        return this.answeredRequests.toString();
    }

    @Override
    public void addInterestOpsListener(final InterestOpsListener opsListener) {
        interestOpsListeners.add(opsListener);
    }
    
}
