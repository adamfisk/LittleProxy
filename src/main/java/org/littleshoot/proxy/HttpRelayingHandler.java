package org.littleshoot.proxy;

import java.util.LinkedList;
import java.util.Queue;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that simply relays traffic from a remote server the proxy is 
 * connected to back to the browser.
 */
public class HttpRelayingHandler extends SimpleChannelUpstreamHandler {
    
    private final Logger log = 
        LoggerFactory.getLogger(HttpRelayingHandler.class);
    
    private volatile boolean readingChunks;
    
    private final Channel browserToProxyChannel;

    private final ChannelGroup channelGroup;

    private final HttpFilter httpFilter;

    private HttpResponse httpResponse;

    /**
     * The current, most recent HTTP request we're processing. This changes
     * as multiple requests come in on the same persistent HTTP 1.1 connection.
     */
    private HttpRequest currentHttpRequest;

    private final HttpRequestHandler requestHandler;

    private final String hostAndPort;

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     * @param hostAndPort Host and port we're relaying to.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel, 
        final ChannelGroup channelGroup, 
        final HttpRequestHandler requestHandler, final String hostAndPort) {
        this (browserToProxyChannel, channelGroup, new NoOpHttpFilter(),
            requestHandler, hostAndPort);
    }

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     * @param filter The HTTP filter.
     * @param hostAndPort Host and port we're relaying to.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel,
        final ChannelGroup channelGroup, final HttpFilter filter,
        final HttpRequestHandler requestHandler, final String hostAndPort) {
        this.browserToProxyChannel = browserToProxyChannel;
        this.channelGroup = channelGroup;
        this.httpFilter = filter;
        this.requestHandler = requestHandler;
        this.hostAndPort = hostAndPort;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        
        final Object messageToWrite;
        
        final boolean flush;
        if (!readingChunks) {
            final HttpResponse hr = (HttpResponse) e.getMessage();
            httpResponse = hr;
            final HttpResponse response;
            
            // Double check the Transfer-Encoding, since it gets tricky.
            final String te = hr.getHeader(HttpHeaders.Names.TRANSFER_ENCODING);
            if (StringUtils.isNotBlank(te) && 
                te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
                if (hr.getProtocolVersion() != HttpVersion.HTTP_1_1) {
                    log.warn("Fixing HTTP version.");
                    response = ProxyUtils.copyMutableResponseFields(hr, 
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, hr.getStatus()));
                    if (!response.containsHeader(HttpHeaders.Names.TRANSFER_ENCODING)) {
                        log.info("Adding chunked encoding header");
                        response.addHeader(HttpHeaders.Names.TRANSFER_ENCODING, 
                            HttpHeaders.Values.CHUNKED);
                    }
                }
                else {
                    response = hr;
                }
            }
            else {
                response = hr;
            }

            if (response.isChunked()) {
                log.info("Starting to read chunks");
                readingChunks = true;
                flush = false;
            }
            else {
                flush = true;
            }
            final HttpResponse filtered = 
                this.httpFilter.filterResponse(response);
            messageToWrite = filtered;
            
            log.info("Headers sent to browser: ");
            ProxyUtils.printHeaders((HttpMessage) messageToWrite);
            
            // An HTTP response is associated with a single request, so we
            // can pop the correct request off the queue.
            // 
            // TODO: I'm a little unclear as to when the request queue would
            // ever actually be empty, but it is from time to time in practice.
            // We've seen this particularly when behind proxies that govern
            // access control on local networks, likely related to redirects.
            if (!this.requestQueue.isEmpty()) {
                this.currentHttpRequest = this.requestQueue.remove();
            }
        } else {
            log.info("Processing a chunk");
            final HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                readingChunks = false;
                flush = true;
            }
            else {
                flush = false;
            }
            messageToWrite = chunk;
        }
        
        if (browserToProxyChannel.isOpen()) {
            final ChannelFuture future = 
                browserToProxyChannel.write(
                    new ProxyHttpResponse(this.currentHttpRequest, httpResponse, 
                        messageToWrite));

            final ChannelFutureListener cfl = 
                ProxyUtils.newWriteListener(this.currentHttpRequest,  
                    httpResponse, messageToWrite);
            if (flush) {
                browserToProxyChannel.write(
                    ChannelBuffers.EMPTY_BUFFER).addListener(cfl);
            } else {
                future.addListener(cfl);
            }
        }
        else {
            log.info("Channel not open. Connected? {}", 
                browserToProxyChannel.isConnected());
            // This will undoubtedly happen anyway, but just in case.
            if (e.getChannel().isOpen()) {
                log.warn("Closing channel to remote server");
                e.getChannel().close();
            }
        }
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel ch = cse.getChannel();
        log.info("New channel opened from proxy to web: {}", ch);
        this.channelGroup.add(ch);
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        log.warn("Got closed event on proxy -> web connection: {}",
            e.getChannel());
        
        log.warn("Closing browser to proxy channel: {}",browserToProxyChannel);
        // This is vital this take place here and only here. If we handle this
        // in other listeners, it's possible to get close events before
        // we actually receive the HTTP response, in which case the response
        // might never get back to the browser. It has to do with the order
        // listeners are called in.
        //closeOnFlush(browserToProxyChannel);
        
        // Doesn't seem like we should close the connection to the browser 
        // here, as there can be multiple connections to external sites for
        // a single connection from the browser.
        this.requestHandler.onRelayChannelClose(ctx, e, browserToProxyChannel, 
            this.hostAndPort);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        log.warn("Caught exception on proxy -> web connection: "+
            e.getChannel(), e.getCause());
        if (e.getChannel().isOpen()) {
            log.warn("Closing open connection");
            closeOnFlush(e.getChannel());
        }
        else {
            // We've seen odd cases where channels seem to continually attempt
            // connections. Make sure we explicitly close the connection here.
            log.warn("Closing connection even though isOpen is false");
            e.getChannel().close();
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private void closeOnFlush(final Channel ch) {
        log.info("Closing channel on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(
                ChannelFutureListener.CLOSE);
        }
    }

    private final Queue<HttpRequest> requestQueue = 
        new LinkedList<HttpRequest>();
    
    /**
     * Adds this HTTP request. We need to keep track of all encoded requests
     * because we ultimately need the request data to determine whether or not
     * we can cache responses. It's a queue because we're dealing with HTTP 1.1
     * persistent connections, and we need to match all requests with responses.
     * 
     * @param request The HTTP request to add.
     */
    public void requestEncoded(final HttpRequest request) {
        this.requestQueue.add(request);
    }
}
