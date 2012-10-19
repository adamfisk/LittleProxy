package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;
import static org.littleshoot.proxy.ProxyUtils.UTF8_CHARSET;

import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
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
public class HttpRequestHandler extends SimpleChannelUpstreamHandler implements RelayListener {

    private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);
    
    private volatile boolean readingChunks;
    
    private static final AtomicInteger totalBrowserToProxyConnections = 
        new AtomicInteger(0);
    private final AtomicInteger browserToProxyConnections = 
        new AtomicInteger(0);
    
    private final Map<SocketAddress, Queue<ChannelFuture>> externalHostsToChannelFutures = 
        new ConcurrentHashMap<SocketAddress, Queue<ChannelFuture>>();
    
    private final AtomicInteger messagesReceived = 
        new AtomicInteger(0);
    
    private final AtomicInteger unansweredRequestCount = 
        new AtomicInteger(0);
    
    private final AtomicInteger requestsSent = 
        new AtomicInteger(0);
    
    private final AtomicInteger responsesReceived = 
        new AtomicInteger(0);
    
    private final ProxyAuthorizationManager authorizationManager;
    
    private final Set<HttpRequest> unansweredHttpRequests = 
        new HashSet<HttpRequest>();

    private ChannelFuture currentChannelFuture;
    
    /**
     * This lock is necessary for when a second chunk arrives in a request
     * before we've even created the current channel future.
     */
    private final Object channelFutureLock = new Object();
    
    private final ChainProxyManager chainProxyManager;
    private final ChannelGroup channelGroup;
    
    private final AtomicBoolean browserChannelClosed = new AtomicBoolean(false);
    private volatile boolean receivedChannelClosed = false;
    
    private final RelayPipelineFactoryFactory relayPipelineFactoryFactory;
    private ClientSocketChannelFactory clientChannelFactory;
    
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
    
    /**
     * Creates a new class for handling HTTP requests with no frills.
     * 
     * @param relayPipelineFactoryFactory The factory for creating factories
     * for channels to relay data from external sites back to clients.
     * @param clientChannelFactory The factory for creating outgoing channels
     * to external sites.
     */
    public HttpRequestHandler(
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory,
        final ClientSocketChannelFactory clientChannelFactory) {
        this(null, null, relayPipelineFactoryFactory, clientChannelFactory);
    }
    
    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * @param authorizationManager The class that handles any 
     * proxy authentication requirements.
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param relayPipelineFactoryFactory The factory for creating factories
     * for channels to relay data from external sites back to clients.
     * @param clientChannelFactory The factory for creating outgoing channels
     * to external sites.
     */
    public HttpRequestHandler(final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory, 
        final ClientSocketChannelFactory clientChannelFactory) {
        this(authorizationManager, channelGroup, null,
            relayPipelineFactoryFactory, clientChannelFactory);
    }
    
    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * @param authorizationManager The class that handles any 
     * proxy authentication requirements.
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param chainProxyManager upstream proxy server host and port or null 
     * if none used.
     * @param relayPipelineFactoryFactory The relay pipeline factory.
     * @param clientChannelFactory The factory for creating outgoing channels
     * to external sites.
     */
    public HttpRequestHandler(ProxyAuthorizationManager authorizationManager, 
        ChannelGroup channelGroup, 
        ChainProxyManager chainProxyManager, 
        RelayPipelineFactoryFactory relayPipelineFactoryFactory, 
        ClientSocketChannelFactory clientChannelFactory) {
        
        LOG.info("Creating new request handler...");
        
        this.clientChannelFactory = clientChannelFactory;
        this.authorizationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.chainProxyManager = chainProxyManager;
        this.relayPipelineFactoryFactory = relayPipelineFactoryFactory;
    }
    
    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        if (browserChannelClosed.get()) {
            LOG.info("Ignoring message since the connection to the browser " +
                "is about to close");
            return;
        }
        messagesReceived.incrementAndGet();
        LOG.debug("Received {} total messages", messagesReceived);
        if (!readingChunks) {
            processRequest(ctx, me);
        } 
        else {
            processChunk(me);
        }
    }

    private void processChunk(final MessageEvent me) {
        LOG.info("Processing chunk...");
        final HttpChunk chunk = (HttpChunk) me.getMessage();
        
        // Remember this will typically be a persistent connection, so we'll
        // get another request after we're read the last chunk. So we need to
        // reset it back to no longer read in chunk mode.
        if (chunk.isLast()) {
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
                if (chunk.isLast()) {
                    LOG.info("Received last chunk -- setting proxy auth " +
                        "chunking to false");
                    this.pendingRequestChunks = false;
                    
                    //me.getChannel().close();
                }
                LOG.info("Ignoring chunk with chunked post for edge case");
                return;
            } else {
                // Note this can happen quite often in tests when requests are
                // arriving very quickly on the same JVM but is less likely
                // to occur in deployed servers.
                LOG.error("NO CHANNEL FUTURE!!");
                synchronized (this.channelFutureLock) {
                    if (this.currentChannelFuture == null) {
                        try {
                            LOG.debug("Waiting for channel future!");
                            channelFutureLock.wait(4000);
                        } catch (final InterruptedException e) {
                            LOG.info("Interrupted!!", e);
                        }
                    }
                }
            }
        }
        
        // We don't necessarily know the channel is connected yet!! This can
        // happen if the client sends a chunk directly after the initial 
        // request.

        if (this.currentChannelFuture.getChannel().isConnected()) {
            this.currentChannelFuture.getChannel().write(chunk);
        }
        else {
            this.currentChannelFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future) 
                    throws Exception {
                    currentChannelFuture.getChannel().write(chunk);
                }
            });
        }
    }
    
    private void processRequest(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        
        final HttpRequest request = (HttpRequest) me.getMessage();
        
        final Channel inboundChannel = me.getChannel();
        this.unansweredRequestCount.incrementAndGet();
        
        LOG.info("Got request: {} on channel: {}", request, inboundChannel);
        if (this.authorizationManager != null && 
            !this.authorizationManager.handleProxyAuthorization(request, ctx)) {
            LOG.info("Not authorized!!");
            // We need to do a few things here. First, if the request is 
            // chunked, we need to make sure we read the full request/POST
            // message body.
            handleFutureChunksIfNecessary(request);
            return;
        } else {
            this.pendingRequestChunks = false;
        }
        
        SocketAddress address = null;
        try {
            address = chainProxyManager.getChainProxy(request);
        } catch (Exception err) {
            LOG.error("Exception", err);
        }
        
        if (address == null) {
            LOG.warn("No host and port found in {}", request.getUri());
            badGateway(request, inboundChannel);
            handleFutureChunksIfNecessary(request);
            return;
        }
        
        final class OnConnect {
            public ChannelFuture onConnect(final ChannelFuture cf) {
                if (request.getMethod() != HttpMethod.CONNECT) {
                    final ChannelFuture writeFuture = 
                        cf.getChannel().write(request);
                    writeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(final ChannelFuture future) 
                            throws Exception {
                            unansweredHttpRequests.add(request);
                            requestsSent.incrementAndGet();
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
        
        final ChannelFuture curFuture = getChannelFuture(address);
        if (curFuture != null) {
            LOG.info("Using existing connection...");
            
            // We don't notify here because the current channel future will not
            // have been null before this assignment.
            if (this.currentChannelFuture == null) {
                LOG.error("Should not be null here");
            }
            this.currentChannelFuture = curFuture;
            if (curFuture.getChannel().isConnected()) {
                onConnect.onConnect(curFuture);
            }
            else {
                final ChannelFutureListener cfl = new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        onConnect.onConnect(curFuture);
                    }
                };
                curFuture.addListener(cfl);
            }
        }
        else {
            LOG.info("Establishing new connection");
            final ChannelFuture cf;
            ctx.getChannel().setReadable(false);
            try {
                cf = newChannelFuture(request, inboundChannel, address);
            } catch (Exception e) {
                LOG.error("Could not resolve host?", e);
                badGateway(request, inboundChannel);
                handleFutureChunksIfNecessary(request);
                ctx.getChannel().setReadable(true);
                return;
            }
            
            final class LocalChannelFutureListener implements ChannelFutureListener {
                
                private final SocketAddress address;

                LocalChannelFutureListener(SocketAddress address) {
                    this.address = address;
                }
                @Override
                public void operationComplete(final ChannelFuture future)
                    throws Exception {
                    final Channel channel = future.getChannel();
                    if (channelGroup != null) {
                        channelGroup.add(channel);
                    }
                    if (future.isSuccess()) {
                        LOG.info("Connected successfully to: {}", channel);
                        LOG.info("Writing message on channel...");
                        final ChannelFuture wf = onConnect.onConnect(cf);
                        wf.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(final ChannelFuture wcf)
                                throws Exception {
                                LOG.info("Finished write: {} to: {} {}", wcf, request.getMethod(), request.getUri());
                                ctx.getChannel().setReadable(true);
                            }
                        });
                        currentChannelFuture = wf;
                        synchronized(channelFutureLock) {
                            channelFutureLock.notifyAll();
                        }
                    }
                    else {
                        
                        Throwable cause = future.getCause();
                        
                        if (LOG.isInfoEnabled()) {
                            LOG.info("Could not connect to {}", address, cause);
                        }
                        
                        boolean tryAgain = chainProxyManager.onCommunicationError(address, cause);
                        
                        if (!tryAgain) {
                            // We call the relay channel closed event handler
                            // with one associated unanswered request.
                            onRelayChannelClose(inboundChannel, address, 1, true);
                            
                        } else {
                            // TODO I am not sure about this
                            removeProxyToWebConnection(address);
                            // try again with different hostAndPort
                            processRequest(ctx, me);
                        }
                    }
                }
            }
            
            cf.addListener(new LocalChannelFutureListener(address));
        }
            
        if (request.isChunked()) {
            readingChunks = true;
        }
    }
    
    
    private void badGateway(final HttpRequest request, 
        final Channel inboundChannel) {
        final HttpResponse response = 
            new DefaultHttpResponse(HttpVersion.HTTP_1_1, 
                HttpResponseStatus.BAD_GATEWAY);
        response.setHeader(HttpHeaders.Names.CONNECTION, "close");
        final String body = "Bad Gateway: "+request.getUri();
        response.setContent(ChannelBuffers.copiedBuffer(body, UTF8_CHARSET));
        response.setHeader(HttpHeaders.Names.CONTENT_LENGTH, body.length());
        inboundChannel.write(response);
    }

    private void handleFutureChunksIfNecessary(final HttpRequest request) {
        if (request.isChunked()) {
            this.pendingRequestChunks = true;
            readingChunks = true;
        }
    }
    @Override
    public void onChannelAvailable(SocketAddress address, ChannelFuture cf) {
        
        synchronized (this.externalHostsToChannelFutures) {
            final Queue<ChannelFuture> futures = 
                this.externalHostsToChannelFutures.get(address);
            
            final Queue<ChannelFuture> toUse;
            if (futures == null) {
                toUse = new LinkedList<ChannelFuture>();
                this.externalHostsToChannelFutures.put(address, toUse);
            } else {
                toUse = futures;
            }
            toUse.add(cf);
        }
    }

    private ChannelFuture getChannelFuture(SocketAddress address) {
        synchronized (this.externalHostsToChannelFutures) {
            final Queue<ChannelFuture> futures = 
                this.externalHostsToChannelFutures.get(address);
            if (futures == null) {
                return null;
            }
            if (futures.isEmpty()) {
                return null;
            }
            final ChannelFuture cf = futures.remove();

            if (cf != null && cf.isSuccess() && 
                !cf.getChannel().isConnected()) {
                // In this case, the future successfully connected at one
                // time, but we're no longer connected. We need to remove the
                // channel and open a new one.
                removeProxyToWebConnection(address);
                return null;
            }
            return cf;
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
            LOG.warn("Connecting on port other than 443!!");
            final String statusLine = "HTTP/1.1 502 Proxy Error\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine, 
                ProxyUtils.PROXY_ERROR_HEADERS);
            ProxyUtils.closeOnFlush(browserToProxyChannel);
        }
        else {
            browserToProxyChannel.setReadable(false);
            
            // We need to modify both the pipeline encoders and decoders for the
            // browser to proxy channel -- the outgoing channel already has
            // the correct handlers and such set at this point.
            ctx.getPipeline().remove("encoder");
            ctx.getPipeline().remove("decoder");
            ctx.getPipeline().remove("handler");
            
            // Note there are two HttpConnectRelayingHandler for each HTTP
            // CONNECT tunnel -- one writing to the browser, and one writing
            // to the remote host.
            ctx.getPipeline().addLast("handler", 
                new HttpConnectRelayingHandler(outgoingChannel, this.channelGroup));
        }
        
        // This is sneaky -- thanks to Emil Goicovici from the list --
        // We temporarily add in a request encoder if we're chaining, allowing
        // us to forward along the HTTP CONNECT request. We then remove that
        // encoder as soon as it's written since past that point we simply
        // want to relay all data.
        
        SocketAddress chainProxy = null;
        try {
            chainProxy = chainProxyManager.getChainProxy(httpRequest);
        } catch (Exception err) {
            LOG.error("Exception", err);
        }
        
        if (chainProxy != null) {
            // forward the CONNECT request to the upstream proxy server 
            // which will return a HTTP response
            outgoingChannel.getPipeline().addBefore("handler", "encoder", 
                new HttpRequestEncoder());
            outgoingChannel.write(httpRequest).addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(final ChannelFuture future)
                    throws Exception {
                    outgoingChannel.getPipeline().remove("encoder");
                }
            });
            
        } else {
            String statusLine = "HTTP/1.1 200 Connection established\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine,
                ProxyUtils.CONNECT_OK_HEADERS);
        }
        
        browserToProxyChannel.setReadable(true);
    }

    private ChannelFuture newChannelFuture(HttpRequest httpRequest, 
            final Channel browserToProxyChannel, SocketAddress address) throws Exception {
        
        // Configure the client.
        final ClientBootstrap cb = 
            new ClientBootstrap(this.clientChannelFactory);
        
        final ChannelPipelineFactory cpf;
        if (httpRequest.getMethod() == HttpMethod.CONNECT) {
            // In the case of CONNECT, we just want to relay all data in both 
            // directions. We SHOULD make sure this is traffic on a reasonable
            // port, however, such as 80 or 443, to reduce security risks.
            cpf = new ChannelPipelineFactory() {
                @Override
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
            cpf = relayPipelineFactoryFactory.getRelayPipelineFactory(
                httpRequest, browserToProxyChannel, this);
        }
            
        cb.setPipelineFactory(cpf);
        cb.setOption("connectTimeoutMillis", 40*1000);
        LOG.debug("Starting new connection to: {}", address);
        
        return cb.connect(address);
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel inboundChannel = cse.getChannel();
        LOG.debug("New channel opened: {}", inboundChannel);
        totalBrowserToProxyConnections.incrementAndGet();
        browserToProxyConnections.incrementAndGet();
        LOG.debug("Now {} browser to proxy channels...", totalBrowserToProxyConnections);
        LOG.debug("Now this class has {} browser to proxy channels...", browserToProxyConnections);
        
        // We need to keep track of the channel so we can close it at the end.
        if (this.channelGroup != null) {
            this.channelGroup.add(inboundChannel);
        }
    }
    
    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) {
        LOG.info("Channel closed: {}", cse.getChannel());
        totalBrowserToProxyConnections.decrementAndGet();
        browserToProxyConnections.decrementAndGet();
        LOG.debug("Now {} total browser to proxy channels...", totalBrowserToProxyConnections);
        LOG.debug("Now this class has {} browser to proxy channels...", browserToProxyConnections);
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (browserToProxyConnections.get() == 0) {
            LOG.debug("Closing all proxy to web channels for this browser " +
                "to proxy connection!!!");
            final Collection<Queue<ChannelFuture>> allFutures = 
                this.externalHostsToChannelFutures.values();
            for (final Queue<ChannelFuture> futures : allFutures) {
                for (final ChannelFuture future : futures) {
                    final Channel ch = future.getChannel();
                    if (ch.isOpen()) {
                        future.getChannel().close();
                    }
                }
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
        final SocketAddress key, final int unansweredRequestsOnChannel,
        final boolean closedEndsResponseBody) {
        
        if (closedEndsResponseBody) {
            LOG.debug("Close ends response body");
            this.receivedChannelClosed = true;
        }
        LOG.debug("this.receivedChannelClosed: {}", this.receivedChannelClosed);
        
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
                LOG.info("Closing browser to proxy channel");
                ProxyUtils.closeOnFlush(browserToProxyChannel);
            }
        }
        else {
            LOG.info("Not closing browser to proxy channel. Received channel closed is {} and we have {} connections and awaiting {} responses", 
                this.receivedChannelClosed,
                this.externalHostsToChannelFutures.size(), 
                this.unansweredRequestCount );
        }
    }
    

    private void removeProxyToWebConnection(SocketAddress address) {
        // It's probably already been removed at this point, but just in case.
        this.externalHostsToChannelFutures.remove(address);
    }

    @Override
    public void onRelayHttpResponse(Channel browserToProxyChannel,
            SocketAddress address, HttpRequest httpRequest) {
        
        this.unansweredHttpRequests.remove(httpRequest);
        this.unansweredRequestCount.decrementAndGet();
        this.responsesReceived.incrementAndGet();
        // If we've received responses to all outstanding requests and one
        // of those outgoing channels has been closed, we should close the
        // connection to the browser.
        if (this.unansweredRequestCount.get() == 0 && this.receivedChannelClosed) {
            if (!browserChannelClosed.getAndSet(true)) {
                LOG.info("Closing browser to proxy channel on HTTP response");
                ProxyUtils.closeOnFlush(browserToProxyChannel);
            }
        }
        else {
            LOG.info("Not closing browser to proxy channel. Still awaiting {} responses..." +
                "receivedChannelClosed={}", this.unansweredRequestCount, this.receivedChannelClosed);
        }
    }
    
    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        final Channel channel = e.getChannel();
        final Throwable cause = e.getCause();
        if (cause instanceof ClosedChannelException) {
            LOG.warn("Caught an exception on browser to proxy channel: {}",
                channel, cause);
        }
        else {
            LOG.info("Caught an exception on browser to proxy channel: {}",
                channel, cause);
        }
        ProxyUtils.closeOnFlush(channel);
    }

    Set<HttpRequest> getUnansweredHttpRequests() {
      return unansweredHttpRequests;
    }
}
