package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffer;
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
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;
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

    /**
     * Creates a new {@link HttpRelayingHandler} with the specified connection
     * to the browser.
     * 
     * @param browserToProxyChannel The browser connection.
     */
    public HttpRelayingHandler(final Channel browserToProxyChannel) {
        this.m_browserToProxyChannel = browserToProxyChannel;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        if (!readingChunks) {
            final HttpResponse response = (HttpResponse) e.getMessage();

            m_log.warn("STATUS: " + response.getStatus());
            m_log.warn("VERSION: " + response.getProtocolVersion());
            //LOG.warn();

            if (!response.getHeaderNames().isEmpty()) {
                for (String name: response.getHeaderNames()) {
                    for (String value: response.getHeaders(name)) {
                        m_log.warn("HEADER: " + name + " = " + value);
                    }
                }
                //LOG.warn();
            }

            //if (response.getStatus().getCode() == 200 && response.isChunked()) {
            if (response.isChunked()) {
                readingChunks = true;
                //LOG.warn("CHUNKED CONTENT {");
            } else {
                ChannelBuffer content = response.getContent();
                if (content.readable()) {
                    //LOG.warn("CONTENT {");
                    //LOG.warn(content.toString("UTF-8"));
                    //LOG.warn("} END OF CONTENT");
                }
            }
        } else {
            final HttpChunk chunk = (HttpChunk) e.getMessage();
            if (chunk.isLast()) {
                readingChunks = false;
                //LOG.warn("} END OF CHUNKED CONTENT");
            } else {
                //LOG.warn(chunk.getContent().toString("UTF-8"));
                //System.out.flush();
            }
        }
        if (m_browserToProxyChannel.isOpen()) {
            if (!readingChunks) {
                //final HttpResponse response = (HttpResponse) e.getMessage();
                //LOG.warn("Writing message with body length: "+response.getContent().readableBytes());
            }
            final ChannelFutureListener logListener = new ChannelFutureListener() {
                public void operationComplete(final ChannelFuture future) 
                    throws Exception {
                    m_log.warn("Finished writing data");
                }
            };
            
            final Object msg = e.getMessage();
            m_log.warn("Writing message: {}", msg);
            m_browserToProxyChannel.write(msg).addListener(logListener);
        }
        else {
            m_log.warn("Channel not open. Connected? {}", 
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
        m_log.warn("New channel opened from proxy to web: {}", ch);
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        m_log.warn("Got closed event on proxy -> web connection: "+e.getChannel());
        //closeOnFlush(m_browserToProxyChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        m_log.warn("Caught exception on proxy -> web connection: "+
            e.getChannel(), e.getCause());
        if (e.getChannel().isOpen()) {
            closeOnFlush(e.getChannel());
        }
    }

    /**
     * Closes the specified channel after all queued write requests are flushed.
     */
    private void closeOnFlush(final Channel ch) {
        m_log.warn("Closing channel on flush: {}", ch);
        if (ch.isConnected()) {
            ch.write(ChannelBuffers.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }
}
