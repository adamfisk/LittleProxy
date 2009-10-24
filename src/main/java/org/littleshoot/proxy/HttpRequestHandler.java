package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.io.UnsupportedEncodingException;
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
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
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
@ChannelPipelineCoverage("one")
public class HttpRequestHandler extends SimpleChannelUpstreamHandler {

    private final static Logger m_log = 
        LoggerFactory.getLogger(HttpRequestHandler.class);
    private volatile HttpRequest request;
    private volatile boolean readingChunks;
    
    private static int s_totalInboundConnections = 0;
    private int m_totalInboundConnections = 0;
    
    private final Map<String, ChannelFuture> m_endpointsToChannelFutures = 
        new ConcurrentHashMap<String, ChannelFuture>();
    
    private volatile int m_messagesReceived = 0;
    
    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) {
        m_messagesReceived++;
        m_log.warn("Received "+m_messagesReceived+" total messages");
        if (!readingChunks) {
            final HttpRequest httpRequest = this.request = (HttpRequest) me.getMessage();
            
            m_log.warn("Got request: {} on channel: "+me.getChannel(), httpRequest);
            
            final Set<String> headerNames = httpRequest.getHeaderNames();
            for (final String name : headerNames) {
                final List<String> values = httpRequest.getHeaders(name);
                m_log.warn(name+": "+values);
                if (name.equalsIgnoreCase("Proxy-Authorization")) {
                    final String fullValue = values.iterator().next();
                    final String value = StringUtils.substringAfter(fullValue, "Basic ").trim();
                    final byte[] decodedValue = Base64.decode(value);
                    try {
                        final String decodedString = new String(decodedValue, "UTF-8");
                    }
                    catch (final UnsupportedEncodingException e) {
                        m_log.error("Could not decode?", e);
                    }
                }
            }
            final String proxyConnectionKey = "Proxy-Connection";
            if (httpRequest.containsHeader(proxyConnectionKey)) {
                final String header = httpRequest.getHeader(proxyConnectionKey);
                httpRequest.removeHeader(proxyConnectionKey);
                httpRequest.setHeader("Connection", header);
            }
            
            //if (!httpRequest.containsHeader("Proxy-Authorization"))
            if (false) {
                final String statusLine = "HTTP/1.1 407 Proxy Authentication Required\r\n";
                final String headers = 
                    "Date: "+ProxyUtils.httpDate()+"\r\n"+
                    "Proxy-Authenticate: Basic realm=\"Restricted Files\"\r\n"+
                    "Content-Length: 415\r\n"+
                    "Content-Type: text/html; charset=iso-8859-1\r\n" +
                    "\r\n";
                
                final String responseBody = 
                    "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n"+
                    "<html><head>\n"+
                    "<title>407 Proxy Authentication Required</title>\n"+
                    "</head><body>\n"+
                    "<h1>Proxy Authentication Required</h1>\n"+
                    "<p>This server could not verify that you\n"+
                    "are authorized to access the document\n"+
                    "requested.  Either you supplied the wrong\n"+
                    "credentials (e.g., bad password), or your\n"+
                    "browser doesn't understand how to supply\n"+
                    "the credentials required.</p>\n"+
                    "</body></html>\n";
                m_log.warn("Content-Length is really: "+responseBody.length());
                writeResponse(ctx.getChannel(), statusLine, headers);
            }
            
            else {
                m_log.warn("Got proxy authorization!");
                final String authentication = 
                    httpRequest.getHeader("Proxy-Authorization");
                m_log.warn(authentication);
                httpRequest.removeHeader("Proxy-Authorization");
            }
            
            final String uri;
            String uriStr = httpRequest.getUri();
            if (uriStr.startsWith("http://www.amazon.com")) {
                final String query = StringUtils.substringAfterLast(uriStr, "?");
                if (StringUtils.isBlank(query)) {
                    uriStr += "?tag=littl08-20";
                }
                uri = uriStr;
            }
            else {
                uri = httpRequest.getUri();
            }
            
            m_log.warn("Using URI: "+uri);
            final String noHostUri = ProxyUtils.stripHost(uri);
            
            final HttpMethod method = httpRequest.getMethod();
            final HttpRequest httpRequestCopy = 
                new DefaultHttpRequest(httpRequest.getProtocolVersion(), 
                    method, noHostUri);
            
            final ChannelBuffer originalContent = httpRequest.getContent();
            
            if (originalContent != null) {
                m_log.warn("Setting content");
                httpRequestCopy.setContent(originalContent);
            }
            
            m_log.warn("Request copy method: {}", httpRequestCopy.getMethod());
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
            final String hostAndPort = parseHostAndPort(httpRequest);
            
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
         
            // We synchronized to avoid creating duplicate connections to the
            // same host, which we shouldn't for a single connection from the
            // browser. Note the synchronization here is short-lived, however,
            // due to the asynchronous connection establishment.
            synchronized (m_endpointsToChannelFutures) {
                final ChannelFuture curFuture = 
                    m_endpointsToChannelFutures.get(hostAndPort);
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
                    final ChannelFuture cf = 
                        newChannelFuture(httpRequestCopy, hostAndPort, inboundChannel);
                    final OnConnect onConnect = new OnConnect(cf);
                    m_endpointsToChannelFutures.put(hostAndPort, cf);
                    cf.addListener(new ChannelFutureListener() {
                        public void operationComplete(final ChannelFuture future)
                            throws Exception {
                            if (future.isSuccess()) {
                                m_log.warn("Connected successfully to: {}", future.getChannel());
                                final Channel newChannel = cf.getChannel();
                                newChannel.getCloseFuture().addListener(
                                    new ChannelFutureListener() {
                                    public void operationComplete(
                                        final ChannelFuture closeFuture)
                                        throws Exception {
                                        m_log.warn("Got an outbound channel close event. Removing channel: "+newChannel);
                                        m_log.warn("Channel open??" +newChannel.isOpen());
                                        m_endpointsToChannelFutures.remove(hostAndPort);
                                        m_log.warn("Outgoing channels on this connection: "+
                                            m_endpointsToChannelFutures.size());
                                        if (m_endpointsToChannelFutures.isEmpty()) {
                                            m_log.warn("All outbound channels closed...");
                                            
                                            // We *don't* want to close here because
                                            // the external site may have closed
                                            // the connection due to a 
                                            // Connection: close header, but we may
                                            // not have actually processed the 
                                            // response yet, and the client side
                                            // is likely expecting more responses
                                            // on this connection.
                                            if (inboundChannel.isOpen()) {
                                                m_log.warn("Closing on flush...");
                                                closeOnFlush(inboundChannel);
                                            }
                                        }
                                        else {
                                            m_log.warn("Existing connections: {}", m_endpointsToChannelFutures);
                                        }
                                    }
                                });
                                
                                m_log.warn("Writing message on channel...");
                                final ChannelFuture wf = onConnect.onConnect();
                                //final ChannelFuture wf = newChannel.write(httpRequestCopy);
                                wf.addListener(new ChannelFutureListener() {
                                    public void operationComplete(final ChannelFuture wcf)
                                        throws Exception {
                                        m_log.warn("Finished write: "+wcf+ " to: "+
                                            httpRequest.getMethod()+" "+httpRequest.getUri());
                                    }
                                });
                            }
                            else {
                                m_log.error("Could not connect!!", future.getCause());
                            }
                        }
                    });
                }
            }
                
            if (request.isChunked()) {
                readingChunks = true;
            }
        } 
        else {
            m_log.error("PROCESSING CHUNK!!!!");
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
            writeResponse(browserToProxyChannel, statusLine, headers);
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
                new HttpConnectRelayingHandler(outgoingChannel));
            
            final String statusLine = "HTTP/1.1 200 Connection established\r\n";
            final String via = newVia();
            final String headers = 
                "Connection: Keep-Alive\r\n"+
                "Proxy-Connection: Keep-Alive\r\n"+
                via + 
                "\r\n";
            writeResponse(browserToProxyChannel, statusLine, headers);
        }
    }

    private void writeResponse(final Channel browserToProxyChannel,
        final String statusLine, final String headers) {
        final String fullResponse = statusLine + headers;
        m_log.warn("Writing full response:\n"+fullResponse);
        try {
            final ChannelBuffer buf = 
                ChannelBuffers.copiedBuffer(fullResponse.getBytes("UTF-8"));
            browserToProxyChannel.write(buf);
            browserToProxyChannel.setReadable(true);
            return;
        }
        catch (final UnsupportedEncodingException e) {
            // Never.
            return;
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
                        new HttpConnectRelayingHandler(browserToProxyChannel));
                    return pipeline;
                }
            };
            }
        else {
            cpf = new ChannelPipelineFactory() {
                public ChannelPipeline getPipeline() throws Exception {
                    // Create a default pipeline implementation.
                    final ChannelPipeline pipeline = pipeline();
                    pipeline.addLast("decoder", new HttpResponseDecoder(4096*2, 81920, 81920));
                    pipeline.addLast("encoder", new HttpRequestEncoder());
                    pipeline.addLast("handler", new HttpRelayingHandler(browserToProxyChannel));
                    return pipeline;
                }
            };
        }
            
        // Set up the event pipeline factory.
        cb.setPipelineFactory(cpf);
        cb.setOption("connectTimeoutMillis", 60*1000);

        // Start the connection attempt.
        m_log.warn("Starting new connection to: "+hostAndPort);
        final ChannelFuture future = cb.connect(new InetSocketAddress(host, port));
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
        m_log.warn("Got URI: "+hostAndPort);
        return hostAndPort;
    }

    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel inboundChannel = cse.getChannel();
        m_log.warn("New channel opened: {}", inboundChannel);
        s_totalInboundConnections++;
        m_totalInboundConnections++;
        m_log.warn("Now "+s_totalInboundConnections+" browser to proxy channels...");
        m_log.warn("Now this class has "+m_totalInboundConnections+" browser to proxy channels...");
    }
    
    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) {
        m_log.warn("Channel closed: {}", cse.getChannel());
        s_totalInboundConnections--;
        m_totalInboundConnections--;
        m_log.warn("Now "+s_totalInboundConnections+" browser to proxy channels...");
        m_log.warn("Now this class has "+m_totalInboundConnections+" browser to proxy channels...");
        
        // The following should always be the case with
        // @ChannelPipelineCoverage("one")
        if (m_totalInboundConnections == 0) {
            m_log.warn("Closing all outgoing channels for this browser connection!!!");
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
        m_log.warn("Caught an exception on browser to proxy channel: "+channel, e.getCause());
        if (channel.isOpen()) {
            closeOnFlush(channel);
        }
    }
    
    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private static void closeOnFlush(final Channel ch) {
        m_log.warn("Closing on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
