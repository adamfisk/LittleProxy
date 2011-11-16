package org.littleshoot.proxy;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.ExceptionEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.channel.group.ChannelGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that simply relays traffic the channel this is connected to to 
 * another channel passed in to the constructor.
 */
@Sharable
public class HttpConnectRelayingHandler extends SimpleChannelUpstreamHandler {
    
    private static final Logger LOG = 
        LoggerFactory.getLogger(HttpConnectRelayingHandler.class);
    
    /**
     * The channel to relay to. This could be a connection from the browser
     * to the proxy or it could be a connection from the proxy to an external
     * site.
     */
    protected final Channel relayChannel;

    protected final ChannelGroup channelGroup;

    /**
     * Creates a new {@link HttpConnectRelayingHandler} with the specified 
     * connection to relay to..
     * 
     * @param relayChannel The channel to relay messages to.
     * @param channelGroup The group of channels to close on shutdown.
     */
    public HttpConnectRelayingHandler(final Channel relayChannel, 
        final ChannelGroup channelGroup) {
        this.relayChannel = relayChannel;
        this.channelGroup = channelGroup;
    }

    @Override
    public void messageReceived(final ChannelHandlerContext ctx, 
        final MessageEvent e) throws Exception {
        final ChannelBuffer msg = (ChannelBuffer) e.getMessage();
        if (relayChannel.isConnected()) {
            final ChannelFutureListener logListener = 
                new ChannelFutureListener() {
                public void operationComplete(final ChannelFuture future) 
                    throws Exception {
                    LOG.debug("Finished writing data on CONNECT channel");
                }
            };
            relayChannel.write(msg).addListener(logListener);
        }
        else {
            LOG.info("Channel not open. Connected? {}", 
                relayChannel.isConnected());
            // This will undoubtedly happen anyway, but just in case.
            ProxyUtils.closeOnFlush(e.getChannel());
        }
    }
    
    @Override
    public void channelOpen(final ChannelHandlerContext ctx, 
        final ChannelStateEvent cse) throws Exception {
        final Channel ch = cse.getChannel();
        LOG.info("New CONNECT channel opened from proxy to web: {}", ch);
        if (this.channelGroup != null) {
            this.channelGroup.add(ch);
        }
    }

    @Override
    public void channelClosed(final ChannelHandlerContext ctx, 
        final ChannelStateEvent e) throws Exception {
        LOG.info("Got closed event on proxy -> web connection: {}", 
            e.getChannel());
        ProxyUtils.closeOnFlush(this.relayChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final ExceptionEvent e) throws Exception {
        LOG.warn("Caught exception on proxy -> web connection: "+
            e.getChannel(), e.getCause());
        ProxyUtils.closeOnFlush(e.getChannel());
    }
}
