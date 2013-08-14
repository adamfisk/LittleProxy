package org.littleshoot.proxy.newstyle;

import static org.littleshoot.proxy.newstyle.ConnectionState.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Queue;

import org.littleshoot.proxy.ProxyUtils;

/**
 * Represents a connection from our proxy to a server.
 */
public class ProxyToServerConnection extends ProxyConnection {

    private final ClientToProxyConnection clientToProxyConnection;
    private final InetSocketAddress address;

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

    public ProxyToServerConnection(EventLoopGroup proxyToServerWorkerPool,
            ChannelGroup channelGroup,
            ClientToProxyConnection clientToProxyConnection,
            InetSocketAddress address) {
        super(DISCONNECTED, proxyToServerWorkerPool, channelGroup);
        this.clientToProxyConnection = clientToProxyConnection;
        this.address = address;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected ConnectionState readInitial(HttpObject httpObject) {
        final HttpResponse response = (HttpResponse) httpObject;
        LOG.debug("Received raw response: {}", response);

        rememberCurrentRequest();
        rememberCurrentResponse(response);

        respondWith(httpObject);

        return ProxyUtils.isChunked(httpObject) ? AWAITING_CHUNK
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

    public Future<Void> write(Object msg) {
        LOG.debug("Requested write of {}", msg);
        if (DISCONNECTED == currentState) {
            // We're disconnected - connect and write the message
            return connectAndWrite((HttpRequest) msg);
        } else {
            synchronized (connectLock) {
                if (CONNECTING == currentState) {
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
            // Go ahead and try writing
            return super.write(msg);
        }
    };

    @Override
    protected Future<Void> writeHttp(HttpObject httpObject) {
        if (httpObject instanceof HttpRequest) {
            // Remember that we issued this HttpRequest for later
            issuedRequests.add((HttpRequest) httpObject);
        }
        return super.writeHttp(httpObject);
    }

    /**
     * Connects to the server and then writes out the initial request (or
     * upgrades to an SSL tunnel, depending).
     * 
     * @param initialRequest
     */
    private Future<Void> connectAndWrite(final HttpRequest initialRequest) {
        LOG.debug("Starting new connection to: {}", address);

        currentState = CONNECTING;

        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;

        clientToProxyConnection.connectionToServerStarted(this);

        final Bootstrap cb = new Bootstrap().group(proxyToServerWorkerPool);
        cb.channel(NioSocketChannel.class);
        cb.handler(new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) throws Exception {
                initChannelPipeline(ch.pipeline(), initialRequest);
            };
        });
        cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 40 * 1000);

        final ChannelFuture cf = cb.connect(address);
        cf.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future)
                    throws Exception {
                if (!future.isSuccess()) {
                    LOG.debug("Could not connect to " + address, future.cause());
                    clientToProxyConnection
                            .connectionToServerFailed(initialRequest);
                }
            }
        });

        return cf;
    }

    @Override
    protected void connected(Channel channel) {
        boolean wasHttpCONNECT = initialRequest.getMethod() == HttpMethod.CONNECT;
        synchronized (connectLock) {
            super.connected(channel);
            if (!wasHttpCONNECT) {
                super.write(initialRequest);
            } else {
                // TODO: handle upgrading to tunnel
            }

            // Once we've finished recording our connection and written our
            // initial request, we can notify anyone who is waiting on the
            // connection that it's okay to proceed.
            connectLock.notifyAll();
        }
        clientToProxyConnection
                .connectionToServerSucceeded(ProxyToServerConnection.this);
    }

    private void initChannelPipeline(ChannelPipeline pipeline,
            HttpRequest httpRequest) {
        pipeline.addLast("decoder", new HttpResponseDecoder(8192, 8192 * 2,
                8192 * 2));
        pipeline.addLast("encoder", new HttpRequestEncoder());

        // We close idle connections to remote servers after the
        // specified timeouts in seconds. If we're sending data, the
        // write timeout should be reasonably low. If we're reading
        // data, however, the read timeout is more relevant.
        final HttpMethod method = httpRequest.getMethod();

        // Could be any protocol if it's connect, so hard to say what the
        // timeout should be, if any.
        if (!method.equals(HttpMethod.CONNECT)) {
            final int readTimeoutSeconds;
            final int writeTimeoutSeconds;
            if (method.equals(HttpMethod.POST) || method.equals(HttpMethod.PUT)) {
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
}
