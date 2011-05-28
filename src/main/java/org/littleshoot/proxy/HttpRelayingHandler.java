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
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
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

    private final RelayListener relayListener;

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
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        
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
            final HttpResponse hr = (HttpResponse) e.getMessage();
            log.info("Received raw response: {}", hr);
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
                writeEndBuffer = false;
            }
            else {
                writeEndBuffer = true;
            }
            
            final HttpResponse filtered = 
                this.httpFilter.filterResponse(response);
            messageToWrite = filtered;
            
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
                log.info("Request queue is empty!");
            }
        } else {
            log.info("Processing a chunk");
            final HttpChunk chunk = (HttpChunk) e.getMessage();
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
            ChannelFuture future = 
                this.browserToProxyChannel.write(
                    new ProxyHttpResponse(this.currentHttpRequest, httpResponse, 
                        messageToWrite));

            if (writeEndBuffer) {
                // See the comment on this flag variable above.
                future = browserToProxyChannel.write(ChannelBuffers.EMPTY_BUFFER);
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
            if (wroteFullResponse(httpResponse, messageToWrite)) {
                log.debug("Notifying request handler of completed response.");
                future.addListener(new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture cf) 
                        throws Exception {
                        relayListener.onRelayHttpResponse(browserToProxyChannel, 
                            hostAndPort, currentHttpRequest);
                    }
                });
            }
            if (shouldCloseRemoteConnection(this.currentHttpRequest, 
                httpResponse, messageToWrite)) {
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
                    public void operationComplete(final ChannelFuture cf) 
                        throws Exception {
                        e.getChannel().close();
                    }
                });
            }
            
            if (shouldCloseBrowserConnection(this.currentHttpRequest, 
                httpResponse, messageToWrite)) {
                log.debug("Closing connection to browser after writes");
                future.addListener(new ChannelFutureListener() {
                    public void operationComplete(final ChannelFuture cf) 
                        throws Exception {
                        log.info("Closing browser connection on flush!!");
                        ProxyUtils.closeOnFlush(browserToProxyChannel);
                    }
                });
            }
        }
        else {
            log.debug("Channel not open. Connected? {}", 
                browserToProxyChannel.isConnected());
            // This will undoubtedly happen anyway, but just in case.
            if (e.getChannel().isOpen()) {
                log.warn("Closing channel to remote server -- received a " +
                    "response after the browser connection is closed?");
                e.getChannel().close();
            }
        }
    }
    
    private boolean wroteFullResponse(final HttpResponse res, 
        final Object messageToWrite) {
        // Thanks to Emil Goicovici for identifying a bug in the initial
        // logic for this.
        if (res.isChunked()) {
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
                    log.info("Not closing on middle chunk for {}", req.getUri());
                    return false;
                }
                else {
                    log.info("Last chunk...using normal closing rules");
                }
            }
        }
        
        // Switch the de-facto standard "Proxy-Connection" header to 
        // "Connection" when we pass it along to the remote host.
        final String proxyConnectionKey = "Proxy-Connection";
        if (req.containsHeader(proxyConnectionKey)) {
            final String header = req.getHeader(proxyConnectionKey);
            req.removeHeader(proxyConnectionKey);
            if (req.getProtocolVersion() == HttpVersion.HTTP_1_1) {
                log.info("Switching Proxy-Connection to Connection for " +
                    "analyzing request for close");
                req.setHeader("Connection", header);
            }
        }
        
        if (!HttpHeaders.isKeepAlive(req)) {
            log.info("Closing since request is not keep alive:");
            // Here we simply want to close the connection because the 
            // browser itself has requested it be closed in the request.
            return true;
        }
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
     */
    private boolean shouldCloseRemoteConnection(final HttpRequest req, 
        final HttpResponse res, final Object msg) {
        if (res.isChunked()) {
            // If the response is chunked, we want to return unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (msg != null) {
                if (!ProxyUtils.isLastChunk(msg)) {
                    log.info("Not closing on middle chunk");
                    return false;
                }
                else {
                    log.info("Last chunk...using normal closing rules");
                }
            }
        }
        if (!HttpHeaders.isKeepAlive(req)) {
            log.info("Closing since request is not keep alive:");
            // Here we simply want to close the connection because the 
            // browser itself has requested it be closed in the request.
            return true;
        }
        if (!HttpHeaders.isKeepAlive(res)) {
            log.info("Closing since response is not keep alive:");
            // In this case, we want to honor the Connection: close header 
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the browser, however
            // as it's possible it has other connections open.
            return true;
        }
        log.info("Not closing -- probably keep alive.");
        return false;
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel ch = cse.getChannel();
        log.info("New channel opened from proxy to web: {}", ch);
        if (this.channelGroup != null) {
            this.channelGroup.add(ch);
        }
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        log.info("Got closed event on proxy -> web connection: {}",
            e.getChannel());
        
        // We shouldn't close the connection to the browser 
        // here, as there can be multiple connections to external sites for
        // a single connection from the browser.
        this.relayListener.onRelayChannelClose(browserToProxyChannel, 
            this.hostAndPort, this.requestQueue.size());
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        log.warn("Caught exception on proxy -> web connection: "+
            e.getChannel(), e.getCause());
        if (e.getChannel().isConnected()) {
            log.warn("Closing open connection");
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
}
