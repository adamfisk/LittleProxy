package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
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

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     * @param channelGroup Keeps track of channels to close on shutdown.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel, 
        final ChannelGroup channelGroup) {
        this.m_browserToProxyChannel = browserToProxyChannel;
        this.m_channelGroup = channelGroup;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        final Object messageToWrite;
        if (!readingChunks) {
            final HttpResponse hr = (HttpResponse) e.getMessage();
            final HttpResponse response;
            if (hr.containsHeader("Transfer-Encoding")) {
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
            messageToWrite = response;
        } else {
            final HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                readingChunks = false;
            }
            messageToWrite = chunk;
        }
        if (m_browserToProxyChannel.isOpen()) {
            final ChannelFutureListener logListener = new ChannelFutureListener() {
                public void operationComplete(final ChannelFuture future) 
                    throws Exception {
                    m_log.info("Finished writing data");
                }
            };
            m_browserToProxyChannel.write(messageToWrite).addListener(logListener);
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
