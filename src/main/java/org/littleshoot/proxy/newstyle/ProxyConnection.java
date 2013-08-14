package org.littleshoot.proxy.newstyle;

import static org.littleshoot.proxy.newstyle.ConnectionState.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;

import org.littleshoot.proxy.ProxyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Represents a connection to/from our proxy.
 */
public abstract class ProxyConnection extends
        SimpleChannelInboundHandler<Object> {
    protected static final Logger LOG = LoggerFactory
            .getLogger(ProxyConnection.class);

    protected final EventLoopGroup proxyToServerWorkerPool;
    protected final ChannelGroup channelGroup;
    protected volatile Channel channel;
    protected volatile ConnectionState currentState;

    protected ProxyConnection(ConnectionState initialState,
            EventLoopGroup proxyToServerWorkerPool, ChannelGroup channelGroup) {
        this.currentState = initialState;
        this.proxyToServerWorkerPool = proxyToServerWorkerPool;
        this.channelGroup = channelGroup;
    }

    /***************************************************************************
     * Lifecycle Methods
     **************************************************************************/

    /**
     * This method is called by users of the ProxyConnection to send stuff out
     * over the socket.
     * 
     * @param msg
     * @return a future for the asynchronous write operation
     */
    public Future<Void> write(Object msg) {
        LOG.debug("To: {} writing: {}", channel, msg);
        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }
        try {
            if (msg instanceof HttpObject) {
                return writeHttp((HttpObject) msg);
            } else {
                return writeRaw((ByteBuf) msg);
            }
        } finally {
            LOG.debug("To: {} wrote: {}", channel, msg);
        }
    }

    /**
     * Read is invoked automatically by Netty as message arrive on the socket.
     * 
     * @param msg
     */
    private void read(Object msg) {
        LOG.debug("Reading in state: {}    from {}    read: {}",
                this.currentState, channel, msg);
        switch (this.currentState) {
        case AWAITING_INITIAL:
            this.currentState = this.readInitial((HttpObject) msg);
            break;
        case AWAITING_CHUNK:
            HttpContent chunk = (HttpContent) msg;
            this.readChunk(chunk);
            this.currentState = ProxyUtils.isLastChunk(chunk) ? AWAITING_INITIAL
                    : AWAITING_CHUNK;
            break;
        case TUNNELING:
            this.readRaw((ByteBuf) msg);
            break;
        case AWAITING_PROXY_AUTHENTICATION:
            if (msg instanceof HttpRequest) {
                // Once we get an HttpRequest, try to process it as usual
                this.currentState = this.readInitial((HttpObject) msg);
            } else {
                // Anything that's not an HttpRequest that came in while we're
                // pending authentication gets dropped on the floor. This can
                // happen if the browser already sent us some chunks (e.g. from
                // a POST) after an initial request that turns out to require
                // authentication.
                break;
            }
        case CONNECTING:
            LOG.warn("Attempted to read from connection that's in the process of connecting.  This shouldn't happen.");
            break;
        case DISCONNECT_REQUESTED:
        case DISCONNECTED:
            LOG.info("Ignoring message since the connection to the browser "
                    + "is about to close");
            break;
        }
    }

    /**
     * Implement this to handle reading the initial object (e.g.
     * {@link HttpRequest} or {@link HttpResponse}).
     * 
     * @param httpObject
     * @return
     */
    protected abstract ConnectionState readInitial(HttpObject httpObject);

    /**
     * Implement this to handle reading a chunk in a chunked transfer.
     * 
     * @param chunk
     */
    protected abstract void readChunk(HttpContent chunk);

    /**
     * Implement this to handle reading a raw buffer as they are used in HTTP
     * tunneling.
     * 
     * @param buf
     */
    protected abstract void readRaw(ByteBuf buf);

    /**
     * Writes HttpObjects to the connection (asynchronous).
     * 
     * @param httpObject
     * @return a future for the asynchronous write operation
     */
    protected Future<Void> writeHttp(HttpObject httpObject) {
        Future<Void> future = channel.writeAndFlush(httpObject);
        if (ProxyUtils.isLastChunk(httpObject)) {
            LOG.debug("Writing an empty buffer to signal the end of our chunked transfer");
            future = channel.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }
        return future;
    }

    /**
     * Writes raw buffers to the connection.
     * 
     * @param buf
     * @return a future for the asynchronous write operation
     */
    protected Future<Void> writeRaw(ByteBuf buf) {
        return channel.writeAndFlush(buf);
    }

    protected void connected(Channel channel) {
        this.currentState = AWAITING_INITIAL;
        this.channel = channel;
        this.channelGroup.add(channel);
        LOG.debug("Connected: {}", channel);
    }

    protected void disconnected() {
        this.currentState = DISCONNECTED;
        LOG.debug("Disconnected: {}", channel);
    }

    protected void becameWriteable() {
    }

    protected void stopReading() {
        LOG.debug("Stopped reading from {}", channel);
        this.channel.config().setAutoRead(false);
    }

    protected void resumeReading() {
        LOG.debug("Resumed reading from {}", channel);
        this.channel.config().setAutoRead(true);
    }

    /***************************************************************************
     * State Methods
     **************************************************************************/
    public boolean isSaturated() {
        return !this.channel.isWritable();
    }

    public void disconnect() {
        channel.close();
    }

    /***************************************************************************
     * Netty API Adaptation
     **************************************************************************/
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        this.read(msg);
    }

    /**
     * Only once the Netty Channel is active to we recognize the ProxyConnection
     * as connected.
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.connected(ctx.channel());
    }

    /**
     * As soon as the Netty Channel is inactive, we recognize the
     * ProxyConnection as disconnected.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        super.channelInactive(ctx);
        this.disconnected();
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx)
            throws Exception {
        if (this.channel.isWritable()) {
            this.becameWriteable();
        }
    }
}
