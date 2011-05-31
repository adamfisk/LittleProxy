package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.Collection;
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
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
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
    
    private static volatile int totalBrowserToProxyConnections = 0;
    private volatile int browserToProxyConnections = 0;
    
    private final Map<String, ChannelFuture> externalHostsToChannelFutures = 
        new ConcurrentHashMap<String, ChannelFuture>();
    
    private volatile int messagesReceived = 0;
    
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
     * @param chainProxyHostAndPort upstream proxy server host and port or null 
     * if none used.
     * @param requestFilter An optional filter for HTTP requests.
     * @param useJmx Whether or not to expose debugging properties via JMX.
     */
    public HttpRequestHandler(final ProxyCacheManager cacheManager, 
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ClientSocketChannelFactory clientChannelFactory,
        final String chainProxyHostAndPort, 
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory,
        final boolean useJmx) {
        this.cacheManager = cacheManager;
        this.authorizationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.clientChannelFactory = clientChannelFactory;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
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
        if (browserChannelClosed.get()) {
            log.info("Ignoring message since the connection to the browser " +
                "is about to close");
            return;
        }
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
            externalHostsToChannelFutures.get(hostAndPort);
        
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
        
        final Channel inboundChannel = me.getChannel();
        if (this.cacheManager != null &&
            this.cacheManager.returnCacheHit((HttpRequest)me.getMessage(), 
            inboundChannel)) {
            log.info("Found cache hit! Cache wrote the response.");
            return;
        }
        this.unansweredRequestCount++;
        final HttpRequest request = (HttpRequest) me.getMessage();
        
        log.info("Got request: {} on channel: "+inboundChannel, request);
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
        synchronized (externalHostsToChannelFutures) {
            final ChannelFuture curFuture = getChannelFuture();
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
                externalHostsToChannelFutures.put(hostAndPort, cf);
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
                            
                            // We call the relay channel closed event handler
                            // with one associated unanswered request.
                            onRelayChannelClose(inboundChannel, hostAndPort, 1, 
                                true);
                        }
                    }
                });
            }
        }
            
        if (request.isChunked()) {
            readingChunks = true;
        }
    }

    private ChannelFuture getChannelFuture() {
        final ChannelFuture cf = externalHostsToChannelFutures.get(hostAndPort);
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
            
            // Note there are two HttpConnectRelayingHandler for each HTTP
            // CONNECT tunnel -- one writing to the browser, and one writing
            // to the remote host.
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
                this.externalHostsToChannelFutures.values();
            for (final ChannelFuture future : futures) {
                final Channel ch = future.getChannel();
                if (ch.isOpen()) {
                    future.getChannel().close();
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
        this.unansweredRequestCount -= unansweredRequestsOnChannel;
        if (this.receivedChannelClosed && 
            (this.externalHostsToChannelFutures.isEmpty() || this.unansweredRequestCount == 0)) {
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
        return this.externalHostsToChannelFutures.size();
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
