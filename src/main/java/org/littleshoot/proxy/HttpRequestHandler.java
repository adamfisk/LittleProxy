package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
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
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpChunkAggregator;
import org.jboss.netty.handler.codec.http.HttpContentDecompressor;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseDecoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class for handling all HTTP requests from the browser to the proxy.
 */
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {

    private final static Logger m_log = 
        LoggerFactory.getLogger(HttpRequestHandler.class);
    private volatile HttpRequest m_request;
    private volatile boolean m_readingChunks;
    
    private static int s_totalInboundConnections = 0;
    private int m_totalInboundConnections = 0;
    
    private final Map<String, ChannelFuture> m_endpointsToChannelFutures = 
        new ConcurrentHashMap<String, ChannelFuture>();
    
    private volatile int m_messagesReceived = 0;
    private final ProxyAuthorizationManager m_authorizationManager;
    private String m_hostAndPort;
    private final ChannelGroup m_channelGroup;

    /**
     * {@link Map} of host name and port strings to filters to apply.
     */
    private final Map<String, HttpFilter> filters;
    
    /**
     * Creates a new class for handling HTTP requests with the specified
     * authentication manager.
     * 
     * @param authorizationManager The class that handles any 
     * proxy authentication requirements.
     * @param channelGroup The group of channels for keeping track of all
     * channels we've opened.
     * @param filters HTTP filtering rules.
     */
    public HttpRequestHandler(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters) {
        this.m_authorizationManager = authorizationManager;
        this.m_channelGroup = channelGroup;
        this.filters = filters;
    }
    
    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        m_messagesReceived++;
        m_log.info("Received "+m_messagesReceived+" total messages");
        if (!m_readingChunks) {
            processMessage(ctx, me);
        } 
        else {
            processChunk(ctx, me);
        }
    }

    private void processChunk(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        m_log.warn("Processing chunk...");
        final ChannelFuture cf = 
            m_endpointsToChannelFutures.get(m_hostAndPort);
        cf.getChannel().write(me.getMessage());
    }

    private void processMessage(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        final HttpRequest httpRequest = 
            this.m_request = (HttpRequest) me.getMessage();
        
        m_log.info("Got request: {} on channel: "+me.getChannel(), httpRequest);
        if (!this.m_authorizationManager.handleProxyAuthorization(httpRequest, ctx)) {
            return;
        }
        final String ae = 
            httpRequest.getHeader(HttpHeaders.Names.ACCEPT_ENCODING);
        if (StringUtils.isNotBlank(ae)) {
            // Remove sdch from encodings we accept since we can't decode it.
            final String noSdch = ae.replace(",sdch", "").replace("sdch", "");
            httpRequest.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, noSdch);
            m_log.info("Removed sdch and inserted: {}", noSdch);
        }
        ProxyUtils.printHeaders(httpRequest);
        
        // Switch the de-facto standard "Proxy-Connection" header to 
        // "Connection" when we pass it along to the remote host.
        final String proxyConnectionKey = "Proxy-Connection";
        if (httpRequest.containsHeader(proxyConnectionKey)) {
            final String header = httpRequest.getHeader(proxyConnectionKey);
            httpRequest.removeHeader(proxyConnectionKey);
            httpRequest.setHeader("Connection", header);
        }
        
        final String uri = httpRequest.getUri();
        
        m_log.info("Raw URI before switching from proxy format: {}", uri);
        final String noHostUri = ProxyUtils.stripHost(uri);
        
        final HttpMethod method = httpRequest.getMethod();
        final HttpRequest httpRequestCopy = 
            new DefaultHttpRequest(httpRequest.getProtocolVersion(), 
                method, noHostUri);
        
        final ChannelBuffer originalContent = httpRequest.getContent();
        
        if (originalContent != null) {
            m_log.info("Setting content");
            httpRequestCopy.setContent(originalContent);
        }
        
        m_log.info("Request copy method: {}", httpRequestCopy.getMethod());
        final Set<String> headerNames = httpRequest.getHeaderNames();
        for (final String name : headerNames) {
            final List<String> values = httpRequest.getHeaders(name);
            httpRequestCopy.setHeader(name, values);
        }
        
        final List<String> vias; 
        if (httpRequestCopy.containsHeader(HttpHeaders.Names.VIA)) {
            vias = httpRequestCopy.getHeaders(HttpHeaders.Names.VIA);
        }
        else {
            vias = new LinkedList<String>();
        }
        
        try {
            final InetAddress address = InetAddress.getLocalHost();
            final String host = address.getHostName();
            final String via = 
                httpRequestCopy.getProtocolVersion().getMajorVersion() + 
                "." +
                httpRequestCopy.getProtocolVersion().getMinorVersion() +
                " " +
                host;
            vias.add(via);
            httpRequestCopy.setHeader(HttpHeaders.Names.VIA, vias);
        }
        catch (final UnknownHostException e) {
            // Just don't add the Via.
            m_log.error("Could not get the host", e);
        }
        
        final Channel inboundChannel = me.getChannel();
        this.m_hostAndPort = parseHostAndPort(httpRequest);
        
        final class OnConnect {
            private final ChannelFuture m_cf;
            private OnConnect(final ChannelFuture cf) {
                this.m_cf = cf;
            }
            public ChannelFuture onConnect() {
                if (method != HttpMethod.CONNECT) {
                    return this.m_cf.getChannel().write(httpRequestCopy);
                }
                else {
                    writeConnectResponse(ctx, httpRequest, 
                        this.m_cf.getChannel());
                    return this.m_cf;
                }
            }
        }
     
        // We synchronize to avoid creating duplicate connections to the
        // same host, which we shouldn't for a single connection from the
        // browser. Note the synchronization here is short-lived, however,
        // due to the asynchronous connection establishment.
        synchronized (m_endpointsToChannelFutures) {
            final ChannelFuture curFuture = 
                m_endpointsToChannelFutures.get(m_hostAndPort);
            if (curFuture != null) {
                final OnConnect onConnect = new OnConnect(curFuture);
                if (curFuture.getChannel().isConnected()) {
                    onConnect.onConnect();
                    //curFuture.getChannel().write(httpRequestCopy);
                }
                else {
                    final ChannelFutureListener cfl = new ChannelFutureListener() {
                        public void operationComplete(final ChannelFuture future)
                            throws Exception {
                            onConnect.onConnect();
                            //curFuture.getChannel().write(httpRequestCopy);
                        }
                    };
                    curFuture.addListener(cfl);
                }
            }
            else {
                final ChannelFutureListener closedCfl = new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture closed) 
                        throws Exception {
                        m_endpointsToChannelFutures.remove(m_hostAndPort);
                    }
                };
                final ChannelFuture cf = 
                    newChannelFuture(httpRequestCopy, m_hostAndPort, inboundChannel);
                final OnConnect onConnect = new OnConnect(cf);
                m_endpointsToChannelFutures.put(m_hostAndPort, cf);
                cf.addListener(new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture future)
                        throws Exception {
                        final Channel channel = future.getChannel();
                        m_channelGroup.add(channel);
                        if (future.isSuccess()) {
                            m_log.info("Connected successfully to: {}", future.getChannel());
                            channel.getCloseFuture().addListener(closedCfl);
                            
                            m_log.info("Writing message on channel...");
                            final ChannelFuture wf = onConnect.onConnect();
                            wf.addListener(new ChannelFutureListener() {
                                public void operationComplete(final ChannelFuture wcf)
                                    throws Exception {
                                    m_log.info("Finished write: "+wcf+ " to: "+
                                        httpRequest.getMethod()+" "+httpRequest.getUri());
                                }
                            });
                        }
                        else {
                            m_log.error("Could not connect!!", future.getCause());
                            if (m_totalInboundConnections == 1) {
                                m_log.warn("Closing browser to proxy channel");
                                me.getChannel().close();
                                m_endpointsToChannelFutures.remove(m_hostAndPort);
                            }
                        }
                    }
                });
            }
        }
            
        if (m_request.isChunked()) {
            m_readingChunks = true;
        }
    }

    private void writeConnectResponse(final ChannelHandlerContext ctx, 
        final HttpRequest httpRequest, final Channel outgoingChannel) {
        final String address = httpRequest.getUri();
        final int port = parsePort(address);
        final Channel browserToProxyChannel = ctx.getChannel();
        
        // We don't allow access to any port but 443.
        if (port != 443) {
            final String statusLine = "HTTP/1.1 502 Proxy Error\r\n";
            final String via = newVia();
            final String headers = 
                "Connection: close\r\n"+
                "Proxy-Connection: close\r\n"+
                "Pragma: no-cache\r\n"+
                "Cache-Control: no-cache\r\n" +
                via + 
                "\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine, headers);
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
                new HttpConnectRelayingHandler(outgoingChannel, this.m_channelGroup));
            
            final String statusLine = "HTTP/1.1 200 Connection established\r\n";
            final String via = newVia();
            final String headers = 
                "Connection: Keep-Alive\r\n"+
                "Proxy-Connection: Keep-Alive\r\n"+
                via + 
                "\r\n";
            ProxyUtils.writeResponse(browserToProxyChannel, statusLine, headers);
        }
    }

    private String newVia() {
        String host;
        try {
            final InetAddress localAddress = InetAddress.getLocalHost();
            host = localAddress.getHostName();
        }
        catch (final UnknownHostException e) {
            m_log.error("Could not lookup host", e);
            host = "Unknown";
        }
         
        final String via = "1.1 " + host + "\r\n";
        return via;
    }

    private int parsePort(final String address) {
        if (address.contains(":")) {
            final String portStr = StringUtils.substringAfter(address, ":"); 
            return Integer.parseInt(portStr);
        }
        else {
            return 80;
        }
    }

    private ChannelFuture newChannelFuture(final HttpRequest httpRequest, 
        final String hostAndPort, final Channel browserToProxyChannel) {
        final String host;
        final int port;
        if (hostAndPort.contains(":")) {
            host = StringUtils.substringBefore(hostAndPort, ":");
            final String portString = StringUtils.substringAfter(hostAndPort, ":");
            port = Integer.parseInt(portString);
        }
        else {
            host = hostAndPort;
            port = 80;
        }
        
        // Configure the client.
        final ClientBootstrap cb = new ClientBootstrap(
            new NioClientSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));
        
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
                            m_channelGroup));
                    return pipeline;
                }
            };
        }
        else {
            cpf = new ChannelPipelineFactory() {
                public ChannelPipeline getPipeline() throws Exception {
                    // Create a default pipeline implementation.
                    final ChannelPipeline pipeline = pipeline();
                    pipeline.addLast("decoder", new HttpResponseDecoder());
                    
                    m_log.info("Querying for host and post: {}", hostAndPort);
                    final boolean shouldFilter;
                    final HttpFilter filter = filters.get(hostAndPort);
                    m_log.info("Using filter: {}", filter);
                    if (filter == null) {
                        m_log.info("Filter not found in: {}", filters);
                        shouldFilter = false;
                    }
                    else { 
                        shouldFilter = filter.shouldFilterResponses(httpRequest);
                    }
                    m_log.info("Filtering: "+shouldFilter);
                    
                    // We decompress and aggregate chunks for responses from 
                    // sites we're applying rules to.
                    if (shouldFilter) {
                        pipeline.addLast("inflater", 
                            new HttpContentDecompressor());
                        pipeline.addLast("aggregator",            
                            new HttpChunkAggregator(1024 * 200));//2048576));
                    }
                    pipeline.addLast("encoder", new HttpRequestEncoder());
                    if (shouldFilter) {
                        //pipeline.addLast("handler", 
                        //    m_handlerFactory.newHandler(browserToProxyChannel, 
                        //        m_hostAndPort));
                        pipeline.addLast("handler",
                            new HttpRelayingHandler(browserToProxyChannel, 
                                m_channelGroup, filter));
                    } else {
                        pipeline.addLast("handler",
                            new HttpRelayingHandler(browserToProxyChannel, 
                                m_channelGroup));
                    }
                    return pipeline;
                }
            };
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                // We just re-use the other channel group, so we don't worry
                // about it here.
                cb.releaseExternalResources();
            }
        }));
            
        // Set up the event pipeline factory.
        cb.setPipelineFactory(cpf);
        cb.setOption("connectTimeoutMillis", 30*1000);

        // Start the connection attempt.
        m_log.info("Starting new connection to: "+hostAndPort);
        final ChannelFuture future = 
            cb.connect(new InetSocketAddress(host, port));
        return future;
    }
    
    private String parseHostAndPort(final HttpRequest httpRequest) {
        final String uri = httpRequest.getUri();
        final String tempUri;
        if (!uri.startsWith("http")) {
            // Browsers particularly seem to send requests in this form when
            // they use CONNECT.
            tempUri = uri;
            //return "";
        }
        else {
            // We can't just take a substring from a hard-coded index because it
            // could be either http or https.
            tempUri = StringUtils.substringAfter(uri, "://");
        }
        final String hostAndPort;
        if (tempUri.contains("/")) {
            hostAndPort = tempUri.substring(0, tempUri.indexOf("/"));
        }
        else {
            hostAndPort = tempUri;
        }
        m_log.info("Got URI: "+hostAndPort);
        return hostAndPort;
    }

    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel inboundChannel = cse.getChannel();
        m_log.info("New channel opened: {}", inboundChannel);
        s_totalInboundConnections++;
        m_totalInboundConnections++;
        m_log.info("Now "+s_totalInboundConnections+" browser to proxy channels...");
        m_log.info("Now this class has "+m_totalInboundConnections+" browser to proxy channels...");
        
        // We need to keep track of the channel so we can close it at the end.
        this.m_channelGroup.add(inboundChannel);
    }
    
    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) {
        m_log.info("Channel closed: {}", cse.getChannel());
        s_totalInboundConnections--;
        m_totalInboundConnections--;
        m_log.info("Now "+s_totalInboundConnections+" total browser to proxy channels...");
        m_log.info("Now this class has "+m_totalInboundConnections+" browser to proxy channels...");
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (m_totalInboundConnections == 0) {
            m_log.info("Closing all outgoing channels for this browser connection!!!");
            final Collection<ChannelFuture> futures = 
                this.m_endpointsToChannelFutures.values();
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
        m_log.info("Caught an exception on browser to proxy channel: "+channel, 
            e.getCause());
        if (channel.isOpen()) {
            closeOnFlush(channel);
        }
    }
    
    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private static void closeOnFlush(final Channel ch) {
        m_log.info("Closing on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(
                ChannelFutureListener.CLOSE);
        }
    }
}
