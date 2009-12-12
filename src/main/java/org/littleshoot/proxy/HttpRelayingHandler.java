package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipelineCoverage;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that simply relays traffic from a remote server the proxy is 
 * connected to back to the browser.
 */
@ChannelPipelineCoverage("one")
public class HttpRelayingHandler extends SimpleChannelUpstreamHandler {
    
    private final Logger m_log = 
        LoggerFactory.getLogger(HttpRelayingHandler.class);
    
    private volatile boolean readingChunks;
    
    private final Channel m_browserToProxyChannel;

    private final ChannelGroup m_channelGroup;

    private final HttpResponseProcessorManager m_responseProcessorManager;

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     * @param responseProcessorManager The class that manages response 
     * processors.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel, 
        final ChannelGroup channelGroup, 
        final HttpResponseProcessorManager responseProcessorManager) {
        this.m_browserToProxyChannel = browserToProxyChannel;
        this.m_channelGroup = channelGroup;
        this.m_responseProcessorManager = responseProcessorManager;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        final Object messageToWrite;
        final ChannelFutureListener writeListener;
        if (!readingChunks) {
            final HttpResponse hr = (HttpResponse) e.getMessage();
            final HttpResponse response;
            if (hr.containsHeader(HttpHeaders.Names.TRANSFER_ENCODING)) {
                if (hr.getProtocolVersion() != HttpVersion.HTTP_1_1) {
                    m_log.warn("Fixing HTTP version.");
                    response = ProxyUtils.copyMutableResponseFields(hr, 
                        new DefaultHttpResponse(HttpVersion.HTTP_1_1, hr.getStatus()));
                }
                else {
                    response = hr;
                }
            }
            else {
                response = hr;
            }

            if (response.isChunked()) {
                readingChunks = true;
            } 
            messageToWrite = 
                this.m_responseProcessorManager.processResponse(response);
            
            // Decide whether to close the connection or not.
            final boolean connectionClose = 
                HttpHeaders.Values.CLOSE.equalsIgnoreCase(
                    response.getHeader(HttpHeaders.Names.CONNECTION));
            final boolean http10AndNoKeepAlive =
                response.getProtocolVersion().equals(HttpVersion.HTTP_1_0) &&
                !HttpHeaders.Values.KEEP_ALIVE.equalsIgnoreCase(
                    response.getHeader(HttpHeaders.Names.CONNECTION));
            
            final boolean close = connectionClose || http10AndNoKeepAlive;
            final boolean chunked = 
                HttpHeaders.Values.CHUNKED.equals(
                    response.getHeader(HttpHeaders.Names.TRANSFER_ENCODING));
            if (close && !chunked) {
                writeListener = ChannelFutureListener.CLOSE;
            }
            else {
                // Do nothing.
                writeListener = ProxyUtils.NO_OP_LISTENER;
            }
        } else {
            final HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                readingChunks = false;
                writeListener = ChannelFutureListener.CLOSE;
            }
            else {
                // Do nothing.
                writeListener = ProxyUtils.NO_OP_LISTENER;
            }
            messageToWrite = chunk;
        }
        
        if (m_browserToProxyChannel.isOpen()) {
            m_browserToProxyChannel.write(messageToWrite).addListener(writeListener);
        }
        else {
            m_log.info("Channel not open. Connected? {}", 
                m_browserToProxyChannel.isConnected());
            // This will undoubtedly happen anyway, but just in case.
            if (e.getChannel().isOpen()) {
                e.getChannel().close();
            }
        }
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel ch = cse.getChannel();
        m_log.info("New channel opened from proxy to web: {}", ch);
        this.m_channelGroup.add(ch);
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        m_log.info("Got closed event on proxy -> web connection: "+e.getChannel());
        //closeOnFlush(m_browserToProxyChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        m_log.info("Caught exception on proxy -> web connection: "+
            e.getChannel(), e.getCause());
        if (e.getChannel().isOpen()) {
            closeOnFlush(e.getChannel());
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private void closeOnFlush(final Channel ch) {
        m_log.info("Closing channel on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
