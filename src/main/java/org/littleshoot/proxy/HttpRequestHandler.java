package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.lang.management.ManagementFactory;
import java.net.UnknownHostException;
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
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
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
public class HttpRequestHandler extends SimpleChannelUpstreamHandler 
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

    private final Set<HttpRequest> unansweredHttpRequests = new HashSet<HttpRequest>();

    private ChannelFuture currentChannelFuture;
    
    private final ChainProxyManager chainProxyManager;
    private final ChannelGroup channelGroup;

    private final ClientSocketChannelFactory clientChannelFactory;
    private final ProxyCacheManager cacheManager;
    
    private final AtomicBoolean browserChannelClosed = new AtomicBoolean(false);
    private volatile boolean receivedChannelClosed = false;
    private final boolean useJmx;
    
    private final RelayPipelineFactoryFactory relayPipelineFactoryFactory;
    
    /**
     * Creates a new class for handling HTTP requests with no frills.
     * 
     * @param clientChannelFactory The common channel factory for clients.
     */
    public HttpRequestHandler(
        final ClientSocketChannelFactory clientChannelFactory,
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory) {
        this(null, null, null, clientChannelFactory, null, 
            relayPipelineFactoryFactory, false);
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
        final ClientSocketChannelFactory clientChannelFactory,
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory) {
        this(cacheManager, authorizationManager, channelGroup,
            clientChannelFactory, null, relayPipelineFactoryFactory, false);
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
     * @param chainProxyManager upstream proxy server host and port or null 
     * if none used.
     * @param requestFilter An optional filter for HTTP requests.
     * @param useJmx Whether or not to expose debugging properties via JMX.
     */
    public HttpRequestHandler(final ProxyCacheManager cacheManager, 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ClientSocketChannelFactory clientChannelFactory,
        final ChainProxyManager chainProxyManager, 
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory,
        final boolean useJmx) {
        this.cacheManager = cacheManager;
        this.authorizationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.clientChannelFactory = clientChannelFactory;
        this.chainProxyManager = chainProxyManager;
        this.relayPipelineFactoryFactory = relayPipelineFactoryFactory;
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
                "-"+hashCode();
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
        if (browserChannelClosed.get()) {
            log.info("Ignoring message since the connection to the browser " +
                "is about to close");
            return;
        }
        messagesReceived.incrementAndGet();
        log.info("Received "+messagesReceived+" total messages");
        if (!readingChunks) {
            processRequest(ctx, me);
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
        
        // We don't necessarily know the channel is connected yet!! This can
        // happen if the client sends a chunk directly after the initial 
        // request.
        if (this.currentChannelFuture.getChannel().isConnected()) {
            this.currentChannelFuture.getChannel().write(chunk);
        }
        else {
            this.currentChannelFuture.addListener(new ChannelFutureListener() {
                
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
        if (this.cacheManager != null &&
            this.cacheManager.returnCacheHit((HttpRequest)me.getMessage(), 
            inboundChannel)) {
            log.info("Found cache hit! Cache wrote the response.");
            return;
        }
        this.unansweredRequestCount.incrementAndGet();
        
        log.info("Got request: {} on channel: "+inboundChannel, request);
        if (this.authorizationManager != null && 
            !this.authorizationManager.handleProxyAuthorization(request, ctx)) {
            log.info("Not authorized!!");
            return;
        }
        
        String hostAndPort = null;
        if (this.chainProxyManager != null) {
            hostAndPort = this.chainProxyManager.getChainProxy(request);
        }
        
        if (hostAndPort == null) {
            hostAndPort = ProxyUtils.parseHostAndPort(request);
        }
        
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
        
        final ChannelFuture curFuture = getChannelFuture(hostAndPort);
        if (curFuture != null) {
            log.info("Using existing connection...");
            this.currentChannelFuture = curFuture;
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
            final ChannelFuture cf;
            ctx.getChannel().setReadable(false);
            try {
                cf = newChannelFuture(request, inboundChannel, hostAndPort);
            } catch (final UnknownHostException e) {
                log.warn("Could not resolve host?", e);
                return;
            }
            
            final class LocalChannelFutureListener implements ChannelFutureListener {
                
                private final String hostAndPort;

                LocalChannelFutureListener(final String hostAndPort) {
                    this.hostAndPort = hostAndPort;
                }
            
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
                                
                                ctx.getChannel().setReadable(true);
                            }
                        });
                        currentChannelFuture = wf;
                    }
                    else {
                        log.info("Could not connect to " + hostAndPort, 
                            future.getCause());
                        
                        final String nextHostAndPort;
                        if (chainProxyManager == null) {
                            nextHostAndPort = hostAndPort;
                        }
                        else {
                            chainProxyManager.onCommunicationError(hostAndPort);
                            nextHostAndPort = chainProxyManager.getChainProxy(request);
                        }
                        
                        if (hostAndPort.equals(nextHostAndPort)) {
                            // We call the relay channel closed event handler
                            // with one associated unanswered request.
                            onRelayChannelClose(inboundChannel, hostAndPort, 1,
                                true);
                        }
                        else {
                            // TODO I am not sure about this
                            removeProxyToWebConnection(hostAndPort);
                            // try again with different hostAndPort
                            processRequest(ctx, me);
                        }
                    }
                }
            }
            
            cf.addListener(new LocalChannelFutureListener(hostAndPort));
        }
            
        if (request.isChunked()) {
            readingChunks = true;
        }
    }
    
    
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

    private ChannelFuture getChannelFuture(final String hostAndPort) {
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

            if (cf != null && cf.isSuccess() && 
                !cf.getChannel().isConnected()) {
                // In this case, the future successfully connected at one
                // time, but we're no longer connected. We need to remove the
                // channel and open a new one.
                removeProxyToWebConnection(hostAndPort);
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
            log.warn("Connecting on port other than 443!!");
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
        String chainProxy = null;
        if (chainProxyManager != null) {
            chainProxy = chainProxyManager.getChainProxy(httpRequest);
            if (chainProxy != null) {
                // forward the CONNECT request to the upstream proxy server 
                // which will return a HTTP response
                outgoingChannel.getPipeline().addBefore("handler", "encoder", 
                    new HttpRequestEncoder());
                outgoingChannel.write(httpRequest).addListener(new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        outgoingChannel.getPipeline().remove("encoder");
                    }
                });
            }
        }
        if (chainProxy == null) {
            final String statusLine = "HTTP/1.1 200 Connection established\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine,
                ProxyUtils.CONNECT_OK_HEADERS);
        }
        
        browserToProxyChannel.setReadable(true);
    }

    private ChannelFuture newChannelFuture(final HttpRequest httpRequest, 
        final Channel browserToProxyChannel, String hostAndPort) 
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
            cpf = relayPipelineFactoryFactory.getRelayPipelineFactory(
                httpRequest, browserToProxyChannel, this);
        }
            
        cb.setPipelineFactory(cpf);
        cb.setOption("connectTimeoutMillis", 40*1000);
        log.info("Starting new connection to: {}", hostAndPort);
        return cb.connect(VerifiedAddressFactory.newInetSocketAddress(host, port, 
            LittleProxyConfig.isUseDnsSec()));
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel inboundChannel = cse.getChannel();
        log.info("New channel opened: {}", inboundChannel);
        totalBrowserToProxyConnections.incrementAndGet();
        browserToProxyConnections.incrementAndGet();
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
        totalBrowserToProxyConnections.decrementAndGet();
        browserToProxyConnections.decrementAndGet();
        log.info("Now "+totalBrowserToProxyConnections+
            " total browser to proxy channels...");
        log.info("Now this class has "+browserToProxyConnections+
            " browser to proxy channels...");
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (browserToProxyConnections.get() == 0) {
            log.info("Closing all proxy to web channels for this browser " +
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
    
    public void onRelayChannelClose(final Channel browserToProxyChannel, 
        final String key, final int unansweredRequestsOnChannel,
        final boolean closedEndsResponseBody) {
        if (closedEndsResponseBody) {
            log.info("Close ends response body");
            this.receivedChannelClosed = true;
        }
        log.info("this.receivedChannelClosed: "+this.receivedChannelClosed);
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
                log.info("Closing browser to proxy channel");
                ProxyUtils.closeOnFlush(browserToProxyChannel);
            }
        }
        else {
            log.info("Not closing browser to proxy channel. Still "+
                this.externalHostsToChannelFutures.size()+" connections and awaiting "+
                this.unansweredRequestCount + " responses");
        }
    }
    

    private void removeProxyToWebConnection(final String key) {
        // It's probably already been removed at this point, but just in case.
        this.externalHostsToChannelFutures.remove(key);
    }

    public void onRelayHttpResponse(final Channel browserToProxyChannel,
        final String key, final HttpRequest httpRequest) {
        if (this.useJmx) {
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
        return this.browserToProxyConnections.get();
    }
    
    public int getTotalClientConnections() {
        return totalBrowserToProxyConnections.get();
    }

    public int getOutgoingConnections() {
        return this.externalHostsToChannelFutures.size();
    }

    public int getRequestsSent() {
        return this.requestsSent.get();
    }

    public int getResponsesReceived() {
        return this.responsesReceived.get();
    }

    public String getUnansweredRequests() {
        return this.unansweredRequests.toString();
    }

    public Set<HttpRequest> getUnansweredHttpRequests() {
      return unansweredHttpRequests;
    }

    public String getAnsweredReqeusts() {
        return this.answeredRequests.toString();
    }
}
