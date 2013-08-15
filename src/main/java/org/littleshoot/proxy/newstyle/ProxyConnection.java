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
import io.netty.util.concurrent.GenericFutureListener;

import org.littleshoot.proxy.ProxyUtils;

/**
 * <p>
 * Base class for objects that represent a connection to/from our proxy.
 * </p>
 * <p>
 * A ProxyConnection models a bidirectional message flow on top of a Netty
 * {@link Channel}.
 * </p>
 * <p>
 * The {@link #read(Object)} method is called whenever a new message arrives on
 * the underlying socket.
 * </p>
 * <p>
 * The {@link #write(Object)} method can be called by anyone wanting to write
 * data out of the connection.
 * </p>
 * <p>
 * ProxyConnection has a lifecycle and its current state within that lifecycle
 * is recorded as a {@link ConnectionState}. The allowed states and transitions
 * vary a little depending on the concrete implementation of ProxyConnection.
 * However, all ProxyConnections share the following lifecycle events:
 * </p>
 * 
 * <ul>
 * <li>{@see #connected(Channel)} - Once the underlying channel is active, the
 * ProxyConnection is considered connected and moves into
 * {@link ConnectionState#AWAITING_INITIAL}. The Channel is recorded at this
 * time for later referencing.</li>
 * <li>{@see #disconnected()} - When the underlying channel goes inactive, the
 * ProxyConnection moves into {@link ConnectionState#DISCONNECTED}</li>
 * <li>{@see #becameWriteable()} - When the underlying channel becomes
 * writeable, this callback is invoked.</li>
 * </ul>
 * 
 * <p>
 * By default, incoming data on the underlying channel is automatically read and
 * passed to the {@link #read(Object)} method. Reading can be stopped and
 * resumed using {@link #stopReading()} and {@link #resumeReading()}.
 * </p>
 * 
 * @param <I> the type of "initial" message.  This will be either {@link HttpResponse} or {@link HttpRequest}.
 */

/**
 * 
 * 
 * @param <I>
 */
public abstract class ProxyConnection<I extends HttpObject> extends
        SimpleChannelInboundHandler<Object> {
    protected final ProxyConnectionLogger LOG = new ProxyConnectionLogger(this);

    protected final EventLoopGroup proxyToServerWorkerPool;
    protected final ChannelGroup allChannels;
    protected volatile ChannelHandlerContext ctx;
    protected volatile Channel channel;
    protected volatile ConnectionState currentState;

    /**
     * Construct a new ProxyConnection.
     * 
     * @param initialState
     *            the state in which this connection starts out
     * @param proxyToServerWorkerPool
     *            an {@link EventLoopGroup} that will be used by any outgoing
     *            connections opened by this ProxyConnection.
     * @param allChannels
     *            a ChannelGroup that records all channels in use (useful for
     *            closing these later)
     */
    protected ProxyConnection(ConnectionState initialState,
            EventLoopGroup proxyToServerWorkerPool, ChannelGroup allChannels) {
        this.currentState = initialState;
        this.proxyToServerWorkerPool = proxyToServerWorkerPool;
        this.allChannels = allChannels;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    /**
     * Read is invoked automatically by Netty as messages arrive on the socket.
     * 
     * @param msg
     */
    private void read(Object msg) {
        LOG.debug("Reading: {}", msg);
        switch (this.currentState) {
        case AWAITING_INITIAL:
            this.currentState = this.readInitial((I) msg);
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
                this.currentState = this.readInitial((I) msg);
            } else {
                // Anything that's not an HttpRequest that came in while we're
                // pending authentication gets dropped on the floor. This can
                // happen if the browser already sent us some chunks (e.g. from
                // a POST) after an initial request that turned out to require
                // authentication.
            }
            break;
        case CONNECTING:
            LOG.warn("Attempted to read from connection that's in the process of connecting.  This shouldn't happen.");
            break;
        case HANDSHAKING:
            LOG.warn(
                    "Attempted to read from connection that's in the process of handshaking.  This shouldn't happen.",
                    channel);
            break;
        case DISCONNECT_REQUESTED:
        case DISCONNECTED:
            LOG.info("Ignoring message since the connection to the browser is closed or about to close");
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
    protected abstract ConnectionState readInitial(I httpObject);

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

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * This method is called by users of the ProxyConnection to send stuff out
     * over the socket.
     * 
     * @param msg
     */
    public void write(Object msg) {
        LOG.debug("Writing: {}", msg);
        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }
        try {
            if (msg instanceof HttpObject) {
                writeHttp((HttpObject) msg);
            } else {
                writeRaw((ByteBuf) msg);
            }
        } finally {
            LOG.debug("Wrote: {}", msg);
        }
    }

    /**
     * Writes HttpObjects to the connection (asynchronous).
     * 
     * @param httpObject
     */
    protected void writeHttp(HttpObject httpObject) {
        channel.writeAndFlush(httpObject);
        if (ProxyUtils.isLastChunk(httpObject)) {
            LOG.debug("Writing an empty buffer to signal the end of our chunked transfer");
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER);
        }
    }

    /**
     * Writes raw buffers to the connection.
     * 
     * @param buf
     * @return a future for the asynchronous write operation
     */
    protected void writeRaw(ByteBuf buf) {
        channel.writeAndFlush(buf);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    protected void connected(ChannelHandlerContext ctx) {
        this.currentState = AWAITING_INITIAL;
        this.ctx = ctx;
        this.channel = ctx.channel();
        this.allChannels.add(channel);
        LOG.debug("Connected");
    }

    protected void disconnected() {
        this.currentState = DISCONNECTED;
        LOG.debug("Disconnected");
    }

    protected void becameWriteable() {
    }

    /**
     * Override this to handle exceptions that occurred during asynchronous
     * processing on the {@link Channel}.
     * 
     * @param cause
     */
    protected void exceptionCaught(Throwable cause) {
    }

    /***************************************************************************
     * State/Management
     **************************************************************************/
    public boolean isSaturated() {
        return !this.channel.isWritable();
    }

    /**
     * Disconnects. This will wait for pending writes to be flushed before
     * disconnecting.
     */
    public void disconnect() {
        channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(
                new GenericFutureListener<Future<? super Void>>() {
                    @Override
                    public void operationComplete(Future<? super Void> future)
                            throws Exception {
                        if (channel.isOpen()) {
                            channel.close();
                        }
                    }
                });
    }

    /**
     * Call this to stop reading.
     */
    protected void stopReading() {
        LOG.debug("Stopped reading");
        this.channel.config().setAutoRead(false);
    }

    /**
     * Call this to resume reading.
     */
    protected void resumeReading() {
        LOG.debug("Resumed reading");
        this.channel.config().setAutoRead(true);
    }

    /***************************************************************************
     * Adapting the Netty API
     **************************************************************************/
    @Override
    protected final void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        this.read(msg);
    }

    /**
     * Only once the Netty Channel is active to we recognize the ProxyConnection
     * as connected.
     */
    @Override
    public final void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx);
        this.connected(ctx);
    }

    /**
     * As soon as the Netty Channel is inactive, we recognize the
     * ProxyConnection as disconnected.
     */
    @Override
    public final void channelInactive(ChannelHandlerContext ctx)
            throws Exception {
        super.channelInactive(ctx);
        this.disconnected();
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx)
            throws Exception {
        if (this.channel.isWritable()) {
            this.becameWriteable();
        }
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        this.exceptionCaught(cause);
    }

}
