package org.littleshoot.proxy;

import java.util.LinkedList;
import java.util.Queue;

import org.apache.commons.lang3.StringUtils;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that simply relays traffic from a remote server the proxy is 
 * connected to back to the browser/client.
 */
public class HttpRelayingHandler extends SimpleChannelUpstreamHandler 
    implements InterestOpsListener {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private volatile boolean readingChunks;
    
    private final Channel browserToProxyChannel;

    private final ChannelGroup channelGroup;

    private final HttpFilter httpFilter;

    private HttpResponse originalHttpResponse;

    /**
     * The current, most recent HTTP request we're processing. This changes
     * as multiple requests come in on the same persistent HTTP 1.1 connection.
     */
    private HttpRequest currentHttpRequest;

    private final RelayListener relayListener;

    private final String hostAndPort;

    private boolean closeEndsResponseBody;

    private Channel channel;
    
    /**
     * Lock for synchronizing traffic, as we learn about the writability of a
     * client channel on a different thread than we set the readability of the
     * remote channel to false. As such, we could determine we need to disable
     * reading from the remote channel on one thread, get an event telling us
     * the client channel is then writable, turn reading on, but then 
     * mistakenly turn it off again on the other thread. So we need to
     * synchronize. See old HexDumpProxyInboundHandler.java example
     */
    private final Object trafficLock = new Object();

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     * @param relayListener The relay listener.
     * @param hostAndPort Host and port we're relaying to.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel, 
        final ChannelGroup channelGroup, 
        final RelayListener relayListener, final String hostAndPort) {
        this (browserToProxyChannel, channelGroup, new NoOpHttpFilter(),
            relayListener, hostAndPort);
    }
    
    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     * @param filter The HTTP filter.
     * @param relayListener The relay listener.
     * @param hostAndPort Host and port we're relaying to.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel,
        final ChannelGroup channelGroup, final HttpFilter filter,
        final RelayListener relayListener, final String hostAndPort) {
        this.browserToProxyChannel = browserToProxyChannel;
        this.channelGroup = channelGroup;
        this.httpFilter = filter;
        this.relayListener = relayListener;
        this.hostAndPort = hostAndPort;
        relayListener.addInterestOpsListener(this);
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent me) throws Exception {
        
        final Object messageToWrite;
        
        // This boolean is a flag for whether or not to write a closing, empty
        // "end" buffer after writing the response. We need to do this to 
        // handle the way Netty creates HttpChunks from responses that aren't 
        // in fact chunked from the remote server using 
        // Transfer-Encoding: chunked. Netty turns these into pseudo-chunked
        // responses in cases where the response would otherwise fill up too
        // much memory or where the length of the response body is unknown.
        // This is handy because it means we can start streaming response
        // bodies back to the browser without reading the entire response.
        // The problem is that in these pseudo-cases the last chunk is encoded
        // to null, and this thwarts normal ChannelFutures from propagating
        // operationComplete events on writes to appropriate channel listeners.
        // We work around this by writing an empty buffer in those cases and
        // using the empty buffer's future instead to handle any operations 
        // we need to when responses are fully written back to clients.
        final boolean writeEndBuffer;
        
        if (!readingChunks) {
            final HttpResponse hr = (HttpResponse) me.getMessage();
            log.debug("Received raw response: {}", hr);
            
            // We need to make a copy here because the response will be 
            // modified in various ways before we need to do things like
            // analyze response headers for whether or not to close the 
            // connection (which may not happen for awhile for large, chunked
            // responses, for example).
            originalHttpResponse = ProxyUtils.copyMutableResponseFields(hr, 
                new DefaultHttpResponse(hr.getProtocolVersion(), hr.getStatus()));
            final HttpResponse response;
            
            // Double check the Transfer-Encoding, since it gets tricky.
            final String te = hr.headers().get(HttpHeaders.Names.TRANSFER_ENCODING);
            if (StringUtils.isNotBlank(te) && 
                te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
                if (hr.getProtocolVersion() != HttpVersion.HTTP_1_1) {
                    log.warn("Fixing HTTP version.");
                    response = ProxyUtils.copyMutableResponseFields(hr, 
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, hr.getStatus()));
                    if (!response.headers().contains(HttpHeaders.Names.TRANSFER_ENCODING)) {
                        log.debug("Adding chunked encoding header");
                        response.headers().add(HttpHeaders.Names.TRANSFER_ENCODING, 
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
                log.debug("Starting to read chunks");
                readingChunks = true;
                writeEndBuffer = false;
            }
            else {
                writeEndBuffer = true;
            }
            
            // An HTTP response is associated with a single request, so we
            // can pop the correct request off the queue.
            // 
            // TODO: I'm a little unclear as to when the request queue would
            // ever actually be empty, but it is from time to time in practice.
            // We've seen this particularly when behind proxies that govern
            // access control on local networks, likely related to redirects.
            if (!this.requestQueue.isEmpty()) {
                this.currentHttpRequest = this.requestQueue.remove();
                if (this.currentHttpRequest == null) {
                    log.warn("Got null HTTP request object.");
                }
            } else {
                log.debug("Request queue is empty!");
            }
            messageToWrite = 
                this.httpFilter.filterResponse(this.currentHttpRequest, response);
        } else {
            log.debug("Processing a chunk");
            final HttpChunk chunk = (HttpChunk) me.getMessage();
            if (chunk.isLast()) {
                readingChunks = false;
                writeEndBuffer = true;
            }
            else {
                writeEndBuffer = false;
            }
            messageToWrite = chunk;
        }
        
        if (browserToProxyChannel.isConnected()) {
            // We need to determine whether or not to close connections based
            // on the HTTP request and response *before* the response has 
            // been modified for sending to the browser.
            final boolean closeRemote = 
                shouldCloseRemoteConnection(this.currentHttpRequest, 
                    originalHttpResponse, messageToWrite);
            final boolean closePending =
                shouldCloseBrowserConnection(this.currentHttpRequest, 
                    originalHttpResponse, messageToWrite);
            
            final boolean wroteFullResponse =
                wroteFullResponse(originalHttpResponse, messageToWrite);
            
            if (closeRemote && closeEndsResponseBody(originalHttpResponse)) {
                this.closeEndsResponseBody = true;
            }
                
            ChannelFuture future = 
                this.browserToProxyChannel.write(
                    new ProxyHttpResponse(this.currentHttpRequest, 
                        originalHttpResponse, messageToWrite));

            if (writeEndBuffer) {
                // See the comment on this flag variable above.
                future = browserToProxyChannel.write(ChannelBuffers.EMPTY_BUFFER);
            }
            
            // If browserToProxyChannel is saturated, do not read until 
            // notified in channelInterestChanged().
            // See trafficLock for an explanation of the locking here.
            synchronized (trafficLock) {
                if (!browserToProxyChannel.isWritable()) {
                    log.debug("SETTING UNREADABLE!!");
                    me.getChannel().setReadable(false);
                }
            }
            
            // If we've written the full response, we need to notify the 
            // request handler. This is because sometimes the remote server
            // will signify the end of an HTTP response body through closing
            // the connection. Each incoming client connection can spawn 
            // multiple external server connections, however, so each external 
            // connection close can't necessarily be propagated to result
            // in closing the client connection. In fact, that should only 
            // happen if we're received responses to all outgoing requests or
            // all other external connections are already closed. We notify
            // the request handler of complete HTTP responses here to allow
            // it to adhere to that logic.
            if (wroteFullResponse) {
                log.debug("Notifying request handler of completed response.");
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture cf) 
                        throws Exception {
                        relayListener.onRelayHttpResponse(browserToProxyChannel, 
                            hostAndPort, currentHttpRequest);
                    }
                });
            }
            if (closeRemote) {
                log.debug("Closing remote connection after writing to browser");
                
                // We close after the future has completed to make sure that
                // all the response data is written to the browser -- 
                // closing immediately could trigger a close to the browser 
                // as well before all the data has been written. Note that
                // in many cases a call to close the remote connection will
                // ultimately result in the connection to the browser closing,
                // particularly when there are no more remote connections
                // associated with that browser connection.
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture cf) 
                        throws Exception {
                        if (me.getChannel().isConnected()) {
                            me.getChannel().close();
                        }
                    }
                });
            }
            
            if (closePending) {
                log.debug("Closing connection to browser after writes");
                future.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(final ChannelFuture cf) 
                        throws Exception {
                        log.info("Closing browser connection on flush!!");
                        ProxyUtils.closeOnFlush(browserToProxyChannel);
                    }
                });
            }
            if (wroteFullResponse && (!closePending && !closeRemote)) {
                log.debug("Making remote channel available for requests");
                this.relayListener.onChannelAvailable(hostAndPort,
                    Channels.succeededFuture(me.getChannel()));
            }
        }
        else {
            log.debug("Channel not open. Connected? {}", 
                browserToProxyChannel.isConnected());
            if (me.getChannel().isConnected()) {
                // This can happen with thing like Google's auto-suggest, for
                // example -- when the user presses backspace, the browser 
                // seems to close the connection for that request, sometimes
                // before the response has come through -- i.e. cases where
                // the browser knows it doesn't care about the response
                // anymore.
                
                // Can also happen when the user browses to another page
                // before a page has completely loaded -- lots of cases really.
                log.debug("Closing channel to remote server -- received a " +
                    "response after the browser connection is closed? " +
                    "Current request:\n{}\nResponse:\n{}", 
                    this.currentHttpRequest, me.getMessage());
                
                // This will undoubtedly happen anyway, but just in case.
                me.getChannel().close();
            }
        }
        log.debug("Finished processing message");
    }

    @Override
    public void channelInterestChanged(final ChannelHandlerContext ctx,
        final ChannelStateEvent cse) throws Exception {
        log.debug("OPS CHANGED!!");
    }
    
    @Override
    public void channelConnected(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        log.debug("CHANNEL CONNECTED!!");
        this.channel = ctx.getChannel();
    }
    
    private boolean closeEndsResponseBody(final HttpResponse res) {
        final String cl = res.headers().get(HttpHeaders.Names.CONTENT_LENGTH);
        if (StringUtils.isNotBlank(cl)) {
            return false;
        }
        final String te = res.headers().get(HttpHeaders.Names.TRANSFER_ENCODING);
        if (StringUtils.isNotBlank(te) && 
            te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED))  {
            return false;
        }
        return true;
    }

    private boolean wroteFullResponse(final HttpResponse res, 
        final Object messageToWrite) {
        // Thanks to Emil Goicovici for identifying a bug in the initial
        // logic for this.
        if (res.isChunked()) {
            if (messageToWrite instanceof HttpResponse) {
                
                return false;
            }
            return ProxyUtils.isLastChunk(messageToWrite);
        }
        return true;
    }

    private boolean shouldCloseBrowserConnection(final HttpRequest req, 
        final HttpResponse res, final Object msg) {
        if (res.isChunked()) {
            // If the response is chunked, we want to return unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (msg != null) {
                if (!ProxyUtils.isLastChunk(msg)) {
                    log.debug("Not closing on middle chunk for {}", req.getUri());
                    return false;
                }
                else {
                    log.debug("Last chunk...using normal closing rules");
                }
            }
        }
        
        // Switch the de-facto standard "Proxy-Connection" header to 
        // "Connection" when we pass it along to the remote host.
        final String proxyConnectionKey = "Proxy-Connection";
        if (req.headers().contains(proxyConnectionKey)) {
            final String header = req.headers().get(proxyConnectionKey);
            req.headers().remove(proxyConnectionKey);
            if (req.getProtocolVersion() == HttpVersion.HTTP_1_1) {
                log.debug("Switching Proxy-Connection to Connection for " +
                    "analyzing request for close");
                req.headers().set("Connection", header);
            }
        }
        
        if (!HttpHeaders.isKeepAlive(req)) {
            log.debug("Closing since request is not keep alive:");
            // Here we simply want to close the connection because the 
            // browser itself has requested it be closed in the request.
            return true;
        }
        log.debug("Not closing browser/client to proxy connection " +
            "for request: {}", req);
        return false;
    }

    /**
     * Determines if the remote connection should be closed based on the 
     * request and response pair. If the request is HTTP 1.0 with no 
     * keep-alive header, for example, the connection should be closed.
     * 
     * This in part determines if we should close the connection. Here's the  
     * relevant section of RFC 2616:
     * 
     * "HTTP/1.1 defines the "close" connection option for the sender to 
     * signal that the connection will be closed after completion of the 
     * response. For example,
     * 
     * Connection: close
     * 
     * in either the request or the response header fields indicates that the 
     * connection SHOULD NOT be considered `persistent' (section 8.1) after 
     * the current request/response is complete."
     *
     * @param req The request.
     * @param res The response.
     * @param msg The message.
     * @return Returns true if the connection should close.
     */
    private boolean shouldCloseRemoteConnection(final HttpRequest req, 
        final HttpResponse res, final Object msg) {
        if (res.isChunked()) {
            // If the response is chunked, we want to return unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (msg != null) {
                if (!ProxyUtils.isLastChunk(msg)) {
                    log.debug("Not closing on middle chunk");
                    return false;
                }
                else {
                    log.debug("Last chunk...using normal closing rules");
                }
            }
        }
        if (!HttpHeaders.isKeepAlive(req)) {
            log.debug("Closing since request is not keep alive:{}, ", req);
            // Here we simply want to close the connection because the 
            // browser itself has requested it be closed in the request.
            return true;
        }
        if (!HttpHeaders.isKeepAlive(res)) {
            log.debug("Closing since response is not keep alive:{}", res);
            // In this case, we want to honor the Connection: close header 
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the browser, however
            // as it's possible it has other connections open.
            return true;
        }
        log.debug("Not closing -- response probably keep alive for:\n{}", res);
        return false;
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel ch = cse.getChannel();
        log.debug("New channel opened from proxy to web: {}", ch);
        if (this.channelGroup != null) {
            this.channelGroup.add(ch);
        }
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        log.debug("Got closed event on proxy -> web connection: {}",
            e.getChannel());
        
        // We shouldn't close the connection to the browser 
        // here, as there can be multiple connections to external sites for
        // a single connection from the browser.
        final int unansweredRequests = this.requestQueue.size();
        log.debug("Unanswered requests: {}", unansweredRequests);
        this.relayListener.onRelayChannelClose(browserToProxyChannel, 
            this.hostAndPort, unansweredRequests, this.closeEndsResponseBody);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        final Throwable cause = e.getCause();
        final String message = 
            "Caught exception on proxy -> web connection: "+e.getChannel();
        final boolean warn;
        if (cause != null) {
            final String msg = cause.getMessage();
            if (msg != null && msg.contains("Connection reset by peer")) {
                warn = false;
            } else {
                warn = true;
            }
        } else {
            warn = true;
        }
        if (warn) {
            log.warn(message, cause);
        } else {
            log.debug(message, cause);
        }
        if (e.getChannel().isConnected()) {
            if (warn) {
                log.warn("Closing open connection");
            } else {
                log.debug("Closing open connection");
            }
            ProxyUtils.closeOnFlush(e.getChannel());
        }
        // This can happen if we couldn't make the initial connection due
        // to something like an unresolved address, for example, or a timeout.
        // There will not have been be any requests written on an unopened
        // connection, so there should not be any further action to take here.
    }

    private final Queue<HttpRequest> requestQueue = 
        new LinkedList<HttpRequest>();
    
    /**
     * Adds this HTTP request. We need to keep track of all encoded requests
     * because we ultimately need the request data to determine whether or not
     * we can cache responses. It's a queue because we're dealing with HTTP 1.1
     * persistent connections, and we need to match all requests with responses.
     * 
     * NOTE that this is the original, unmodified request in this case without
     * hop-by-hop headers stripped and without HTTP request filters applied.
     * It's the raw request we received from the client connection.
     * 
     * See ProxyHttpRequestEncoder.
     * 
     * @param request The HTTP request to add.
     */
    public void requestEncoded(final HttpRequest request) {
        this.requestQueue.add(request);
    }

    @Override
    public void channelWritable(final ChannelHandlerContext ctx,
        final ChannelStateEvent cse) {
        // See trafficLock for an explanation of the locking here.
        synchronized (trafficLock) {
            // If inboundChannel is not saturated anymore, continue accepting
            // the incoming traffic from the outboundChannel.
            if (cse.getChannel().isWritable()) {
                if (this.channel != null) {
                    this.channel.setReadable(true);
                }
            }
        }
    }
    
}
