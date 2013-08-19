package org.littleshoot.proxy.newstyle;

import static org.littleshoot.proxy.newstyle.ConnectionState.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.ProxyUtils;

/**
 * Represents a connection from our proxy to a server on the web.
 */
@Sharable
public class ProxyToServerConnection extends ProxyConnection<HttpResponse> {

    private final ClientToProxyConnection clientToProxyConnection;
    protected final InetSocketAddress address;
    private final HttpFilter responseFilter;

    /**
     * While we're in the process of connecting, it's possible that we'll
     * receive a new message to write. This lock helps us synchronize and wait
     * for the connection to be established before writing the next message.
     */
    private Object connectLock = new Object();

    private HttpRequest initialRequest;

    /**
     * Keeps track of HttpRequests that have been issued so that we can
     * associate them with responses that we get back
     */
    private final Queue<HttpRequest> issuedRequests = new LinkedList<HttpRequest>();

    /**
     * While we're doing a chunked transfer, this keeps track of the HttpRequest
     * to which we're responding.
     */
    private HttpRequest currentHttpRequest;

    /**
     * While we're doing a chunked transfer, this keeps track of the initial
     * HttpResponse object for our transfer (which is useful for its headers).
     */
    private HttpResponse currentHttpResponse;

    /**
     * Associates written HttpRequests to copies of the original HttpRequest
     * (before rewriting).
     */
    private Map<HttpRequest, HttpRequest> originalHttpRequests = new HashMap<HttpRequest, HttpRequest>();

    /**
     * Tracks whether or not to filter responses based on the request.
     */
    private Map<HttpRequest, Boolean> filterResponsesByRequests = new HashMap<HttpRequest, Boolean>();

    public ProxyToServerConnection(EventLoopGroup proxyToServerWorkerPool,
            ChannelGroup channelGroup,
            ClientToProxyConnection clientToProxyConnection,
            InetSocketAddress address, HttpFilter responseFilter) {
        super(DISCONNECTED, proxyToServerWorkerPool, channelGroup);
        this.clientToProxyConnection = clientToProxyConnection;
        this.address = address;
        this.responseFilter = responseFilter;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected ConnectionState readInitial(HttpResponse httpResponse) {
        LOG.debug("Received raw response: {}", httpResponse);

        rememberCurrentRequest();
        rememberCurrentResponse(httpResponse);
        filterResponseIfNecessary(httpResponse);
        respondWith(httpResponse);

        return ProxyUtils.isChunked(httpResponse) ? AWAITING_CHUNK
                : AWAITING_INITIAL;
    }

    @Override
    protected void readChunk(HttpContent chunk) {
        respondWith(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        clientToProxyConnection.write(buf);
    }

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * Write an HttpRequest to the server.
     * 
     * @param rewrittenHttpRequest
     *            the request that will get written to the Server, including any
     *            rewriting that has happened
     * @param originalHttpRequest
     *            a copy of the original request
     */
    public void write(HttpRequest rewrittenHttpRequest,
            HttpRequest originalHttpRequest) {
        originalHttpRequests.put(rewrittenHttpRequest, originalHttpRequest);
        this.write(rewrittenHttpRequest);
    }

    public void write(Object msg) {
        LOG.debug("Requested write of {}", msg);
        if (is(DISCONNECTED)) {
            // We're disconnected - connect and write the message
            connectAndWrite((HttpRequest) msg);
        } else {
            synchronized (connectLock) {
                if (is(CONNECTING)) {
                    // We're in the processing of connecting, wait for it to
                    // finish
                    try {
                        connectLock.wait(30000);
                    } catch (InterruptedException ie) {
                        LOG.warn("Interrupted while waiting for connect monitor");
                    }
                }
            }
            LOG.debug("Using existing connection to: {}", address);
            super.write(msg);
        }
    };

    @Override
    protected void writeHttp(HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            if (ProxyUtils.isCONNECT(httpObject)) {
                LOG.debug("Received CONNECT request, initiating TUNNELING mode");
                startTunneling().addListener(
                        new GenericFutureListener<Future<?>>() {
                            public void operationComplete(Future<?> future)
                                    throws Exception {
                                clientToProxyConnection
                                        .serverConnectionIsTunneling(ProxyToServerConnection.this);
                            };
                        });
                return;
            }

            // Remember that we issued this HttpRequest for later
            issuedRequests.add((HttpRequest) httpObject);
        }
        super.writeHttp(httpObject);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    @Override
    protected void connected(ChannelHandlerContext ctx) {
        clientToProxyConnection.finishedConnectingToServer(this,
                initialRequest, true);

        synchronized (connectLock) {
            super.connected(ctx);
            write(initialRequest);
            // Once we've finished recording our connection and written our
            // initial request, we can notify anyone who is waiting on the
            // connection that it's okay to proceed.
            connectLock.notifyAll();
        }
    }

    @Override
    protected void disconnected() {
        super.disconnected();

    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        final String message = "Caught exception on proxy -> web connection: "
                + channel;
        final boolean reportAsError = cause == null
                || cause.getMessage() == null
                || !cause.getMessage().contains("Connection reset by peer");

        if (reportAsError) {
            LOG.error(message, cause);
        } else {
            LOG.warn(message, cause);
        }
        if (channel.isActive()) {
            if (reportAsError) {
                LOG.error("Disconnecting open connection");
            } else {
                LOG.warn("Disconnecting open connection");
            }
            disconnect();
        }
        // This can happen if we couldn't make the initial connection due
        // to something like an unresolved address, for example, or a timeout.
        // There will not have been be any requests written on an unopened
        // connection, so there should not be any further action to take here.
    }

    /***************************************************************************
     * Private Implementation
     **************************************************************************/

    private void filterResponseIfNecessary(HttpResponse httpResponse) {
        if (shouldFilterResponseTo(this.currentHttpRequest)) {
            this.responseFilter.filterResponse(
                    this.originalHttpRequests.get(this.currentHttpRequest),
                    httpResponse);
        }
    }

    /**
     * An HTTP response is associated with a single request, so we can pop the
     * correct request off the queue.
     */
    private void rememberCurrentRequest() {
        LOG.debug("Remembering the current request.");
        // TODO: I'm a little unclear as to when the request queue would
        // ever actually be empty, but it is from time to time in practice.
        // We've seen this particularly when behind proxies that govern
        // access control on local networks, likely related to redirects.
        if (!this.issuedRequests.isEmpty()) {
            this.currentHttpRequest = this.issuedRequests.remove();
            if (this.currentHttpRequest == null) {
                LOG.warn("Got null HTTP request object.");
            }
        } else {
            LOG.debug("Request queue is empty!");
        }
    }

    /**
     * Keeps track of the current HttpResponse so that we can associate its
     * headers with future related chunks for this same transfer.
     * 
     * @param response
     */
    private void rememberCurrentResponse(HttpResponse response) {
        LOG.debug("Remembering the current response.");
        // We need to make a copy here because the response will be
        // modified in various ways before we need to do things like
        // analyze response headers for whether or not to close the
        // connection (which may not happen for awhile for large, chunked
        // responses, for example).
        currentHttpResponse = ProxyUtils.copyMutableResponseFields(response);
    }

    private void respondWith(HttpObject httpObject) {
        clientToProxyConnection.respond(this, currentHttpRequest,
                currentHttpResponse, httpObject);
    }

    /**
     * Connects to the server and then writes out the initial request (or
     * upgrades to an SSL tunnel, depending).
     * 
     * @param initialRequest
     */
    private void connectAndWrite(final HttpRequest initialRequest) {
        LOG.debug("Starting new connection to: {}", address);

        currentState = CONNECTING;

        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;

        clientToProxyConnection.connectingToServer(this);

        final Bootstrap cb = new Bootstrap().group(proxyToServerWorkerPool);
        cb.channel(NioSocketChannel.class);
        cb.handler(new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) throws Exception {
                initChannelPipeline(ch.pipeline(), initialRequest);
            };
        });
        cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 40 * 1000);

        cb.connect(address).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future)
                    throws Exception {
                if (!future.isSuccess()) {
                    LOG.debug("Could not connect to " + address, future.cause());
                    clientToProxyConnection
                            .finishedConnectingToServer(
                                    ProxyToServerConnection.this,
                                    initialRequest, false);
                }

            }
        });
    }

    private void initChannelPipeline(ChannelPipeline pipeline,
            HttpRequest httpRequest) {
        pipeline.addLast("decoder", new HttpResponseDecoder(8192, 8192 * 2,
                8192 * 2));

        // We decompress and aggregate chunks for responses from
        // sites we're applying filtering rules to.
        if (shouldFilterResponseTo(httpRequest)) {
            pipeline.addLast("inflater", new HttpContentDecompressor());
            pipeline.addLast("aggregator", new HttpObjectAggregator(
                    this.responseFilter.getMaxResponseSize()));// 2048576));
        }

        pipeline.addLast("encoder", new HttpRequestEncoder());

        // We close idle connections to remote servers after the
        // specified timeouts in seconds. If we're sending data, the
        // write timeout should be reasonably low. If we're reading
        // data, however, the read timeout is more relevant.

        // Could be any protocol if it's connect, so hard to say what the
        // timeout should be, if any.
        if (!ProxyUtils.isCONNECT(httpRequest)) {
            final int readTimeoutSeconds;
            final int writeTimeoutSeconds;
            if (ProxyUtils.isPOST(httpRequest) || ProxyUtils.isPUT(httpRequest)) {
                readTimeoutSeconds = 0;
                writeTimeoutSeconds = 70;
            } else {
                readTimeoutSeconds = 70;
                writeTimeoutSeconds = 0;
            }
            pipeline.addLast("idle", new IdleStateHandler(readTimeoutSeconds,
                    writeTimeoutSeconds, 0));
        }

        pipeline.addLast("handler", this);
    }

    private boolean shouldFilterResponseTo(HttpRequest httpRequest) {
        // If we've already checked whether to filter responses for a given
        // request, use the original result
        Boolean result = filterResponsesByRequests.get(httpRequest);
        if (result == null) {
            // This is our first time checking whether responses to this request
            // need to be filtered. Check, and then remember for later.
            result = this.responseFilter != null
                    && this.responseFilter.filterResponses(httpRequest);
            filterResponsesByRequests.put(httpRequest, result);
        }
        return result;
    }
}
