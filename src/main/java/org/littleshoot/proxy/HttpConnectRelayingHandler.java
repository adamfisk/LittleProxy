package org.littleshoot.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class that simply relays traffic the channel this is connected to to 
 * another channel passed in to the constructor.
 */
@Sharable
public class HttpConnectRelayingHandler extends SimpleChannelInboundHandler<ByteBuf> {
    
    private static final Logger LOG = 
        LoggerFactory.getLogger(HttpConnectRelayingHandler.class);
    
    /**
     * The channel to relay to. This could be a connection from the browser
     * to the proxy or it could be a connection from the proxy to an external
     * site.
     */
    private final Channel relayChannel;

    private final ChannelGroup channelGroup;

    /**
     * Creates a new {@link HttpConnectRelayingHandler} with the specified 
     * connection to relay to..
     * 
     * @param relayChannel The channel to relay messages to.
     * @param channelGroup The group of channels to close on shutdown.
     */
    public HttpConnectRelayingHandler(final Channel relayChannel, 
        final ChannelGroup channelGroup) {
        // Fail fast if these are null.
        if (relayChannel == null) {
            throw new NullPointerException("Relay channel is null!");
        }
        if (channelGroup == null) {
            throw new NullPointerException("Channel group is null!!");
        }
        this.relayChannel = relayChannel;
        this.channelGroup = channelGroup;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg)
            throws Exception {
        if (relayChannel.isActive()) {
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
                relayChannel.isActive());
            // This will undoubtedly happen anyway, but just in case.
            ProxyUtils.closeOnFlush(ctx.channel());
        }
    }
    
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        final Channel ch = ctx.channel();
        LOG.info("New CONNECT channel opened from proxy to web: {}", ch);
        this.channelGroup.add(ch);
    }
    
    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        LOG.info("Got closed event on proxy -> web connection: {}", 
            ctx.channel());
        ProxyUtils.closeOnFlush(this.relayChannel);
    }

    @Override
    public void exceptionCaught(final ChannelHandlerContext ctx, 
        final Throwable t) throws Exception {
        LOG.info("Caught exception on proxy -> web connection: "+
            ctx.channel(), t);
        ProxyUtils.closeOnFlush(ctx.channel());
    }
}
