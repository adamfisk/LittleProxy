package org.littleshoot.proxy.impl;

import static org.littleshoot.proxy.impl.ConnectionState.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.nio.NioUdtByteConnectorChannel;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;

import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolError;

/**
 * Represents a connection from our proxy to a server on the web.
 */
@Sharable
public class ProxyToServerConnection extends ProxyConnection<HttpResponse> {

    private final ClientToProxyConnection clientConnection;
    private volatile TransportProtocol transportProtocol;
    private volatile SSLContext sslContext;
    private volatile InetSocketAddress address;
    private final String serverHostAndPort;
    private volatile String chainedProxyHostAndPort;
    private final HttpFilter responseFilter;

    /**
     * While we're in the process of connecting, it's possible that we'll
     * receive a new message to write. This lock helps us synchronize and wait
     * for the connection to be established before writing the next message.
     */
    private final Object connectLock = new Object();

    /**
     * Encapsulates the flow for establishing a connection, which can vary
     * depending on how things are configured.
     */
    private volatile ConnectionFlow connectionFlow;

    /**
     * This is the initial request received prior to connecting. We keep track
     * of it so that we can process it after connection finishes.
     */
    private volatile HttpRequest initialRequest;

    /**
     * Keeps track of HttpRequests that have been issued so that we can
     * associate them with responses that we get back
     */
    private final Queue<HttpRequest> issuedRequests = new LinkedList<HttpRequest>();

    /**
     * While we're doing a chunked transfer, this keeps track of the HttpRequest
     * to which we're responding.
     */
    private volatile HttpRequest currentHttpRequest;

    /**
     * While we're doing a chunked transfer, this keeps track of the initial
     * HttpResponse object for our transfer (which is useful for its headers).
     */
    private volatile HttpResponse currentHttpResponse;

    /**
     * Associates written HttpRequests to copies of the original HttpRequest
     * (before rewriting).
     */
    private final Map<HttpRequest, HttpRequest> originalHttpRequests = new ConcurrentHashMap<HttpRequest, HttpRequest>();

    /**
     * Cache of whether or not to filter responses based on the request.
     */
    private final Map<HttpRequest, Boolean> shouldFilterResponseCache = new ConcurrentHashMap<HttpRequest, Boolean>();

    /**
     * Keeps track of whether or not we're acting as MITM.
     */
    private final AtomicBoolean isMITM = new AtomicBoolean(false);

    ProxyToServerConnection(
            DefaultHttpProxyServer proxyServer,
            ClientToProxyConnection clientConnection,
            TransportProtocol transportProtocol, SSLContext sslContext,
            InetSocketAddress address, String serverHostAndPort,
            String chainedProxyHostAndPort, HttpFilter responseFilter) {
        super(DISCONNECTED, proxyServer, sslContext, true);
        this.clientConnection = clientConnection;
        this.transportProtocol = transportProtocol;
        this.sslContext = sslContext;
        this.address = address;
        this.serverHostAndPort = serverHostAndPort;
        this.chainedProxyHostAndPort = chainedProxyHostAndPort;
        this.responseFilter = responseFilter;
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected void read(Object msg) {
        if (msg instanceof ConnectionTracer) {
            // Record statistic for ConnectionTracer and then ignore it
            clientConnection.recordBytesReceivedFromServer(this,
                    (ConnectionTracer) msg);
        } else if (isConnecting()) {
            LOG.debug("Reading: {}", msg);
            this.connectionFlow.read(msg);
        } else {
            super.read(msg);
        }
    }

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
        clientConnection.write(buf);
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
    void write(HttpRequest rewrittenHttpRequest,
            HttpRequest originalHttpRequest) {
        originalHttpRequests.put(rewrittenHttpRequest, originalHttpRequest);
        this.write(rewrittenHttpRequest);
    }

    void write(Object msg) {
        LOG.debug("Requested write of {}", msg);
        if (is(DISCONNECTED)) {
            // We're disconnected - connect and write the message
            if (msg instanceof HttpRequest) {
                connectAndWrite((HttpRequest) msg);
            } else {
                LOG.warn(
                        "Received non-httprequest while disconnected, this shouldn't happen: {}",
                        msg);
            }
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
            HttpRequest httpRequest = (HttpRequest) httpObject;
            // Remember that we issued this HttpRequest for later
            issuedRequests.add(httpRequest);
            // Track stats
            clientConnection.recordRequestSentToServer(this, httpRequest);
        }
        super.writeHttp(httpObject);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    @Override
    protected void becameSaturated() {
        super.becameSaturated();
        this.clientConnection.serverBecameSaturated(this);
    }

    @Override
    protected void becameWriteable() {
        super.becameWriteable();
        this.clientConnection.serverBecameWriteable(this);
    }

    @Override
    protected void disconnected() {
        super.disconnected();
        clientConnection.serverDisconnected(this);
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        String message = "Caught exception on proxy -> web connection";
        boolean reportAsError = cause == null
                || cause.getMessage() == null
                || !cause.getMessage().contains("Connection reset by peer");

        if (reportAsError) {
            LOG.error(message, cause);
        } else {
            LOG.warn(message, cause);
        }
        if (!is(DISCONNECTED)) {
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
     * State Management
     **************************************************************************/
    public TransportProtocol getTransportProtocol() {
        return transportProtocol;
    }

    public InetSocketAddress getAddress() {
        return address;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public String getChainedProxyHostAndPort() {
        return chainedProxyHostAndPort;
    }

    public HttpRequest getInitialRequest() {
        return initialRequest;
    }

    /***************************************************************************
     * Private Implementation
     **************************************************************************/

    /**
     * An HTTP response is associated with a single request, so we can pop the
     * correct request off the queue.
     */
    private void rememberCurrentRequest() {
        LOG.debug("Remembering the current request.");
        // I'm a little unclear as to when the request queue would
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
        clientConnection.respond(this, currentHttpRequest,
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

        // Remember our initial request so that we can write it after connecting
        this.initialRequest = initialRequest;
        initializeConnectionFlow();
        connectionFlow.start();
    }

    /**
     * This method initializes our {@link ConnectionFlow} based on however this
     * connection has been configured.
     */
    private void initializeConnectionFlow() {
        this.connectionFlow = new ConnectionFlow(clientConnection, this,
                connectLock)
                .startWith(connectChannel);

        if (sslContext != null) {
            this.connectionFlow.then(encryptChannel);
        }

        if (ProxyUtils.isCONNECT(initialRequest)) {
            if (clientConnection.shouldChain(initialRequest)) {
                // If we're chaining to another proxy, send over the CONNECT
                // request
                this.connectionFlow.then(httpCONNECTWithChainedProxy);
                // TODO: add back MITM support
                // } else if (this.proxyServer.isUseMITMInSSL()) {
                // startCONNECTWithMITM();
            }

            this.connectionFlow.then(startTunneling)
                    .then(clientConnection.respondCONNECTSuccessful)
                    .then(clientConnection.startTunneling);
        }
    }

    private ConnectionFlowStep connectChannel = new ConnectionFlowStep(this,
            CONNECTING) {

        @Override
        protected Future<?> execute() {
            Bootstrap cb = new Bootstrap().group(proxyServer
                    .getProxyToServerWorkerFor(transportProtocol));

            switch (transportProtocol) {
            case TCP:
                LOG.debug("Connecting to server with TCP");
                cb.channel(NioSocketChannel.class);
                break;
            case UDT:
                LOG.debug("Connecting to server with UDT");
                cb.channel(NioUdtByteConnectorChannel.class);
                break;
            default:
                throw new UnknownTransportProtocolError(transportProtocol);
            }

            cb.handler(new ChannelInitializer<Channel>() {
                protected void initChannel(Channel ch) throws Exception {
                    initChannelPipeline(ch.pipeline(), initialRequest);
                };
            });
            cb.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 40 * 1000);

            return cb.connect(address);
        }
    };

    private ConnectionFlowStep encryptChannel = new ConnectionFlowStep(this,
            HANDSHAKING) {
        protected Future<?> execute() {
            return encrypt();
        }
    };

    private ConnectionFlowStep httpCONNECTWithChainedProxy = new ConnectionFlowStep(
            this, AWAITING_CONNECT_OK) {
        protected Future<?> execute() {
            LOG.debug("Handling CONNECT request through Chained Proxy");
            return writeToChannel(initialRequest);
        }

        void onSuccess(ConnectionFlow flow) {
            // Do nothing, since we want to wait for the CONNECT response to
            // come back
        }

        void read(ConnectionFlow flow, Object msg) {
            // Here we're handling the response from a chained proxy to our
            // earlier CONNECT request
            boolean connectOk = false;
            if (msg instanceof HttpResponse) {
                HttpResponse httpResponse = (HttpResponse) msg;
                int statusCode = httpResponse.getStatus().code();
                if (statusCode >= 200 && statusCode <= 299) {
                    connectOk = true;
                }
            }
            if (connectOk) {
                flow.go();
            } else {
                flow.fail();
            }
        }

    };

    protected void retryConnecting(InetSocketAddress newAddress,
            TransportProtocol transportProtocol,
            String chainedProxyHostAndPort,
            HttpRequest initialRequest) {
        this.address = newAddress;
        this.transportProtocol = transportProtocol;
        this.chainedProxyHostAndPort = chainedProxyHostAndPort;
        this.connectAndWrite(initialRequest);
    }

    private void initChannelPipeline(ChannelPipeline pipeline,
            HttpRequest httpRequest) {
        pipeline.addLast("decoder", new ProxyHttpResponseDecoder(8192,
                8192 * 2,
                8192 * 2));

        // We decompress and aggregate chunks for responses from
        // sites we're applying filtering rules to.
        if (!ProxyUtils.isCONNECT(httpRequest)
                && shouldFilterResponseTo(httpRequest)) {
            pipeline.addLast("inflater", new HttpContentDecompressor());
            pipeline.addLast("aggregator", new HttpObjectAggregator(
                    this.responseFilter.getMaxResponseSize()));// 2048576));
        }

        pipeline.addLast("encoder", new HttpRequestEncoder());

        // Set idle timeout
        if (ProxyUtils.isCONNECT(httpRequest)) {
            // Could be any protocol if it's connect, so hard to say what the
            // timeout should be, if any. Don't set one.
        } else {
            // We close idle connections to remote servers after the
            // specified timeouts in seconds. If we're sending data, the
            // write timeout should be reasonably low. If we're reading
            // data, however, the read timeout is more relevant.
            int readTimeoutSeconds;
            int writeTimeoutSeconds;
            if (ProxyUtils.isPOST(httpRequest) || ProxyUtils.isPUT(httpRequest)) {
                readTimeoutSeconds = 0;
                writeTimeoutSeconds = this.proxyServer
                        .getIdleConnectionTimeout();
            } else {
                readTimeoutSeconds = this.proxyServer
                        .getIdleConnectionTimeout();
                ;
                writeTimeoutSeconds = 0;
            }
            pipeline.addLast("idle", new IdleStateHandler(readTimeoutSeconds,
                    writeTimeoutSeconds, 0));
        }

        pipeline.addLast("handler", this);
    }

    // /**
    // * <p>
    // * Start the flow for establishing a man-in-the-middle tunnel.
    // * </p>
    // *
    // * <p>
    // * See {@link ClientToProxyConnection#finishCONNECTWithMITM()} for the end
    // * of this flow.
    // * </p>
    // *
    // * @return
    // */
    // private void startCONNECTWithMITM() {
    // LOG.debug("Preparing to act as Man-in-the-Middle");
    // this.isMITM.set(true);
    // encrypt().addListener(
    // new GenericFutureListener<Future<? super Channel>>() {
    // @Override
    // public void operationComplete(Future<? super Channel> future)
    // throws Exception {
    // LOG.debug("Proxy to server SSL handshake done. Success is: "
    // + future.isSuccess());
    // finishConnecting(false);
    // }
    // });
    // }

    /**
     * <p>
     * Do all the stuff that needs to be done after connecting/establishing a
     * tunnel.
     * </p>
     * 
     * @param shouldForwardInitialRequest
     *            whether or not we should forward the initial HttpRequest to
     *            the server after the connection has been established.
     */
    void connectionSucceeded(boolean shouldForwardInitialRequest) {
        clientConnection.serverConnectionSucceeded(this);

        synchronized (connectLock) {
            super.connected();

            if (shouldForwardInitialRequest) {
                LOG.debug("Writing initial request");
                write(initialRequest);
            } else {
                LOG.debug("Dropping initial request");
            }

            // Once we've finished recording our connection and written our
            // initial request, we can notify anyone who is waiting on the
            // connection that it's okay to proceed.
            connectLock.notifyAll();
        }
    }

    private void filterResponseIfNecessary(HttpResponse httpResponse) {
        if (shouldFilterResponseTo(this.currentHttpRequest)) {
            this.responseFilter.filterResponse(
                    this.originalHttpRequests.get(this.currentHttpRequest),
                    httpResponse);
        }
    }

    /**
     * <p>
     * Determines whether or not responses to the given request should be
     * filtered. If we were given an {@link HttpFilter} in our constructor, and
     * that filter's {@link HttpFilter#filterResponses(HttpRequest)} method
     * returns true, then we will filter.
     * </p>
     * 
     * <p>
     * To avoid calling {@link HttpFilter#filterResponses(HttpRequest)} multiple
     * times, this method caches the results of that call by HttpRequest.
     * </p>
     * 
     * @param httpRequest
     * @return
     */
    private boolean shouldFilterResponseTo(HttpRequest httpRequest) {
        // If we've already checked whether to filter responses for a given
        // request, use the original result
        Boolean result = shouldFilterResponseCache.get(httpRequest);
        if (result == null) {
            // This is our first time checking whether responses to this request
            // need to be filtered. Check, and then remember for later.
            result = this.responseFilter != null
                    && this.responseFilter.filterResponses(httpRequest);
            shouldFilterResponseCache.put(httpRequest, result);
        }
        return result;
    }
}
