package org.littleshoot.proxy.impl;

import static org.littleshoot.proxy.impl.ConnectionState.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.ReferenceCounted;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;

import javax.net.ssl.SSLEngine;

import org.littleshoot.proxy.SslEngineSource;

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
 * @param <I>
 *            the type of "initial" message. This will be either
 *            {@link HttpResponse} or {@link HttpRequest}.
 */
abstract class ProxyConnection<I extends HttpObject> extends
        SimpleChannelInboundHandler<Object> {
    protected final ProxyConnectionLogger LOG = new ProxyConnectionLogger(this);

    protected final DefaultHttpProxyServer proxyServer;
    protected volatile SslEngineSource sslEngineSource;
    protected final boolean runsAsSSLClient;

    protected volatile ChannelHandlerContext ctx;
    protected volatile Channel channel;

    private volatile ConnectionState currentState;
    private volatile boolean tunneling = false;

    /**
     * If using encryption, this holds our {@link SSLEngine}.
     */
    protected volatile SSLEngine sslEngine;

    /**
     * Construct a new ProxyConnection.
     * 
     * @param initialState
     *            the state in which this connection starts out
     * @param proxyServer
     *            the {@link DefaultHttpProxyServer} in which we're running
     * @param sslEngineSource
     *            (optional) if provided, this connection will be encrypted
     *            using the given an {@link SSLEngine} obtained by calling
     *            {@link SslEngineSource#newSslEngine()}
     * @param runsAsSSLClient
     *            determines whether this connection acts as an SSL client or
     *            server (determines who does the handshake)
     */
    protected ProxyConnection(ConnectionState initialState,
            DefaultHttpProxyServer proxyServer,
            SslEngineSource sslEngineSource,
            boolean runsAsSSLClient) {
        become(initialState);
        this.proxyServer = proxyServer;
        this.sslEngineSource = sslEngineSource;
        this.runsAsSSLClient = runsAsSSLClient;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    /**
     * Read is invoked automatically by Netty as messages arrive on the socket.
     * 
     * @param msg
     */
    protected void read(Object msg) {
        LOG.debug("Reading: {}", msg);

        if (tunneling) {
            // In tunneling mode, this connection is simply shoveling bytes
            readRaw((ByteBuf) msg);
        } else {
            // If not tunneling, then we are always dealing with HttpObjects.
            readHTTP((HttpObject) msg);
        }
    }

    /**
     * Handles reading {@link HttpObject}s.
     * 
     * @param httpObject
     */
    private void readHTTP(HttpObject httpObject) {
        ConnectionState nextState = getCurrentState();
        switch (getCurrentState()) {
        case AWAITING_INITIAL:
            nextState = readHTTPInitial((I) httpObject);
            break;
        case AWAITING_CHUNK:
            HttpContent chunk = (HttpContent) httpObject;
            readHTTPChunk(chunk);
            nextState = ProxyUtils.isLastChunk(chunk) ? AWAITING_INITIAL
                    : AWAITING_CHUNK;
            break;
        case AWAITING_PROXY_AUTHENTICATION:
            if (httpObject instanceof HttpRequest) {
                // Once we get an HttpRequest, try to process it as usual
                nextState = readHTTPInitial((I) httpObject);
            } else {
                // Anything that's not an HttpRequest that came in while
                // we're pending authentication gets dropped on the floor. This
                // can happen if the connected host already sent us some chunks
                // (e.g. from a POST) after an initial request that turned out
                // to require authentication.
            }
            break;
        case CONNECTING:
            LOG.warn("Attempted to read from connection that's in the process of connecting.  This shouldn't happen.");
            break;
        case NEGOTIATING_CONNECT:
            LOG.debug("Attempted to read from connection that's in the process of negotiating an HTTP CONNECT.  This is probably the LastHttpContent of a chunked CONNECT.");
            break;
        case AWAITING_CONNECT_OK:
            LOG.warn("AWAITING_CONNECT_OK should have been handled by ProxyToServerConnection.read()");
            break;
        case HANDSHAKING:
            LOG.warn(
                    "Attempted to read from connection that's in the process of handshaking.  This shouldn't happen.",
                    channel);
            break;
        case DISCONNECT_REQUESTED:
        case DISCONNECTED:
            LOG.info("Ignoring message since the connection is closed or about to close");
            break;
        }
        become(nextState);
    }

    /**
     * Implement this to handle reading the initial object (e.g.
     * {@link HttpRequest} or {@link HttpResponse}).
     * 
     * @param httpObject
     * @return
     */
    protected abstract ConnectionState readHTTPInitial(I httpObject);

    /**
     * Implement this to handle reading a chunk in a chunked transfer.
     * 
     * @param chunk
     */
    protected abstract void readHTTPChunk(HttpContent chunk);

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
    void write(Object msg) {
        if (msg instanceof ReferenceCounted) {
            LOG.debug("Retaining reference counted message");
            ((ReferenceCounted) msg).retain();
        }

        doWrite(msg);
    }

    void doWrite(Object msg) {
        LOG.debug("Writing: {}", msg);

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
     * Writes HttpObjects to the connection asynchronously.
     * 
     * @param httpObject
     */
    protected void writeHttp(HttpObject httpObject) {
        if (ProxyUtils.isLastChunk(httpObject)) {
            channel.write(httpObject);
            LOG.debug("Writing an empty buffer to signal the end of our chunked transfer");
            writeToChannel(Unpooled.EMPTY_BUFFER);
        } else {
            writeToChannel(httpObject);
        }
    }

    /**
     * Writes raw buffers to the connection.
     * 
     * @param buf
     * @return a future for the asynchronous write operation
     */
    protected void writeRaw(ByteBuf buf) {
        writeToChannel(buf);
    }

    /**
     * Encapsulates the writing to the channel. In addition to writing to the
     * channel, this method makes sure that if the channel becomes saturated
     * that the {@link #becameSaturated()} callback gets invoked.
     * 
     * @param msg
     * @return
     */
    protected ChannelFuture writeToChannel(Object msg) {
        ChannelFuture future = channel.writeAndFlush(msg);
        future.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future)
                    throws Exception {
                if (!channel.isWritable()) {
                    becameSaturated();
                }
            }
        });
        return future;
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    /**
     * This method is called as soon as the underlying {@link Channel} is
     * connected. Note that for proxies with complex {@link ConnectionFlow}s
     * that include SSL handshaking and other such things, just because the
     * {@link Channel} is connected doesn't mean that our connection is fully
     * established.
     */
    protected void connected() {
        LOG.debug("Connected");
    }

    /**
     * This method is called as soon as the underlying {@link Channel} becomes
     * disconnected.
     */
    protected void disconnected() {
        become(DISCONNECTED);
        LOG.debug("Disconnected");
    }

    /**
     * <p>
     * Enables tunneling on this connection by dropping the HTTP related
     * encoders and decoders, as well as idle timers.
     * </p>
     * 
     * <p>
     * Note - the work is done on the {@link ChannelHandlerContext}'s executor
     * because {@link ChannelPipeline#remove(String)} can deadlock if called
     * directly.
     * </p>
     */
    protected ConnectionFlowStep StartTunneling = new ConnectionFlowStep(
            this, NEGOTIATING_CONNECT) {
        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        protected Future execute() {
            try {
                ChannelPipeline pipeline = ctx.pipeline();
                if (pipeline.get("encoder") != null) {
                    pipeline.remove("encoder");
                }
                if (pipeline.get("responseWrittenMonitor") != null) {
                    pipeline.remove("responseWrittenMonitor");
                }
                if (pipeline.get("decoder") != null) {
                    pipeline.remove("decoder");
                }
                if (pipeline.get("requestReadMonitor") != null) {
                    pipeline.remove("requestReadMonitor");
                }
                if (pipeline.get("idle") != null) {
                    pipeline.remove("idle");
                }
                tunneling = true;
                return channel.newSucceededFuture();
            } catch (Throwable t) {
                return channel.newFailedFuture(t);
            }
        }
    };

    /**
     * Encrypts traffic on this connection with SSL/TLS.
     * 
     * @return a Future for when the SSL handshake has completed
     */
    protected Future<Channel> encrypt() {
        return encrypt(ctx.pipeline());
    }

    /**
     * Encrypts traffic on this connection with SSL/TLS.
     * 
     * @param pipeline
     *            the ChannelPipeline on which to enable encryption
     * @return a Future for when the SSL handshake has completed
     */
    protected Future<Channel> encrypt(ChannelPipeline pipeline) {
        LOG.debug("Enabling encryption with SSLEngineSource: {}",
                sslEngineSource);
        sslEngine = sslEngineSource.newSslEngine();
        sslEngine.setUseClientMode(runsAsSSLClient);
        sslEngine.setNeedClientAuth(!runsAsSSLClient);
        SslHandler handler = new SslHandler(sslEngine);
        pipeline.addFirst("ssl", handler);
        return handler.handshakeFuture().addListener(
                new GenericFutureListener<Future<? super Channel>>() {
                    @Override
                    public void operationComplete(Future<? super Channel> future)
                            throws Exception {

                    }
                });
    }

    /**
     * Enables decompression and aggregation of content, which is useful for
     * certain types of filtering activity.
     * 
     * @param pipeline
     * @param numberOfBytesToBuffer
     */
    protected void aggregateContentForFiltering(ChannelPipeline pipeline,
            int numberOfBytesToBuffer) {
        pipeline.addLast("inflater", new HttpContentDecompressor());
        pipeline.addLast("aggregator", new HttpObjectAggregator(
                numberOfBytesToBuffer));
    }

    /**
     * Callback that's invoked if this connection becomes saturated.
     */
    protected void becameSaturated() {
        LOG.debug("Became saturated");
    }

    /**
     * Callback that's invoked when this connection becomes writeable again.
     */
    protected void becameWriteable() {
        LOG.debug("Became writeable");
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
    /**
     * Disconnects. This will wait for pending writes to be flushed before
     * disconnecting.
     * 
     * @return Future<Void> for when we're done disconnecting. If we weren't
     *         connected, this returns null.
     */
    Future<Void> disconnect() {
        if (channel == null) {
            return null;
        } else {
            final Promise<Void> promise = channel.newPromise();
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(
                    new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(
                                Future<? super Void> future)
                                throws Exception {
                            closeChannel(promise);
                        }
                    });
            return promise;
        }
    }

    private void closeChannel(final Promise<Void> promise) {
        channel.close().addListener(
                new GenericFutureListener<Future<? super Void>>() {
                    public void operationComplete(
                            Future<? super Void> future)
                            throws Exception {
                        if (future
                                .isSuccess()) {
                            promise.setSuccess(null);
                        } else {
                            promise.setFailure(future
                                    .cause());
                        }
                    };
                });
    }

    /**
     * Indicates whether or not this connection is saturated (i.e. not
     * writeable).
     * 
     * @return
     */
    protected boolean isSaturated() {
        return !this.channel.isWritable();
    }

    /**
     * Utility for checking current state.
     * 
     * @param state
     * @return
     */
    protected boolean is(ConnectionState state) {
        return currentState == state;
    }

    /**
     * If this connection is currently in the process of going through a
     * {@link ConnectionFlow}, this will return true.
     * 
     * @return
     */
    protected boolean isConnecting() {
        return currentState.isPartOfConnectionFlow();
    }

    /**
     * Udpates the current state to the given value.
     * 
     * @param state
     */
    protected void become(ConnectionState state) {
        this.currentState = state;
    }

    protected ConnectionState getCurrentState() {
        return currentState;
    }

    public boolean isTunneling() {
        return tunneling;
    }

    public SSLEngine getSslEngine() {
        return sslEngine;
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

    ProxyConnectionLogger getLOG() {
        return LOG;
    }

    /***************************************************************************
     * Adapting the Netty API
     **************************************************************************/
    @Override
    protected final void channelRead0(ChannelHandlerContext ctx, Object msg)
            throws Exception {
        read(msg);
    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        try {
            this.ctx = ctx;
            this.channel = ctx.channel();
            this.proxyServer.registerChannel(ctx.channel());
        } finally {
            super.channelRegistered(ctx);
        }
    }

    /**
     * Only once the Netty Channel is active to we recognize the ProxyConnection
     * as connected.
     */
    @Override
    public final void channelActive(ChannelHandlerContext ctx) throws Exception {
        try {
            connected();
        } finally {
            super.channelActive(ctx);
        }
    }

    /**
     * As soon as the Netty Channel is inactive, we recognize the
     * ProxyConnection as disconnected.
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        try {
            disconnected();
        } finally {
            super.channelInactive(ctx);
        }
    }

    @Override
    public final void channelWritabilityChanged(ChannelHandlerContext ctx)
            throws Exception {
        try {
            if (this.channel.isWritable()) {
                becameWriteable();
            }
        } finally {
            super.channelWritabilityChanged(ctx);
        }
    }

    @Override
    public final void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        exceptionCaught(cause);
    }

    /**
     * <p>
     * We're looking for {@link IdleStateEvent}s to see if we need to
     * disconnect.
     * </p>
     * 
     * <p>
     * Note - we don't care what kind of IdleState we got. Thanks to <a
     * href="https://github.com/qbast">qbast</a> for pointing this out.
     * </p>
     */
    @Override
    public final void userEventTriggered(ChannelHandlerContext ctx, Object evt)
            throws Exception {
        try {
            if (evt instanceof IdleStateEvent) {
                LOG.debug("Got idle, disconnecting");
                disconnect();
            }
        } finally {
            super.userEventTriggered(ctx, evt);
        }
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     **************************************************************************/

    /**
     * Utility handler for monitoring bytes read on this connection.
     */
    @Sharable
    protected abstract class BytesReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof ByteBuf) {
                    bytesRead(((ByteBuf) msg).readableBytes());
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void bytesRead(int numberOfBytes);
    }

    /**
     * Utility handler for monitoring requests read on this connection.
     */
    @Sharable
    protected abstract class RequestReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof HttpRequest) {
                    requestRead((HttpRequest) msg);
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void requestRead(HttpRequest httpRequest);
    }

    /**
     * Utility handler for monitoring responses read on this connection.
     */
    @Sharable
    protected abstract class ResponseReadMonitor extends
            ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg)
                throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseRead((HttpResponse) msg);
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.channelRead(ctx, msg);
            }
        }

        protected abstract void responseRead(HttpResponse httpResponse);
    }

    /**
     * Utility handler for monitoring bytes written on this connection.
     */
    @Sharable
    protected abstract class BytesWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            try {
                if (msg instanceof ByteBuf) {
                    bytesWritten(((ByteBuf) msg).readableBytes());
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void bytesWritten(int numberOfBytes);
    }

    /**
     * Utility handler for monitoring requests written on this connection.
     */
    @Sharable
    protected abstract class RequestWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            try {
                if (msg instanceof HttpRequest) {
                    requestWritten(((HttpRequest) msg));
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void requestWritten(HttpRequest httpRequest);
    }

    /**
     * Utility handler for monitoring responses written on this connection.
     */
    @Sharable
    protected abstract class ResponseWrittenMonitor extends
            ChannelOutboundHandlerAdapter {
        @Override
        public void write(ChannelHandlerContext ctx,
                Object msg, ChannelPromise promise)
                throws Exception {
            try {
                if (msg instanceof HttpResponse) {
                    responseWritten(((HttpResponse) msg));
                }
            } catch (Throwable t) {
                LOG.warn("Unable to record bytesRead", t);
            } finally {
                super.write(ctx, msg, promise);
            }
        }

        protected abstract void responseWritten(HttpResponse httpResponse);
    }

}
