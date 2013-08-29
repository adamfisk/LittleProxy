package org.littleshoot.proxy.impl;

import static org.littleshoot.proxy.impl.ConnectionState.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ChannelFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
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

import javax.net.ssl.SSLContext;

import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolError;

/**
 * <p>
 * Represents a connection from our proxy to a server on the web.
 * ProxyConnections are reused fairly liberally, and can go from disconnected to
 * connected, back to disconnected and so on.
 * </p>
 * 
 * <p>
 * Connecting a {@link ProxyToServerConnection} can involve more than just
 * connecting the underlying {@link Channel}. In particular, the connection may
 * use encryption (i.e. TLS) and it may also establish an HTTP CONNECT tunnel.
 * The various steps involved in fully establishing a connection are
 * encapsulated in the property {@link #connectionFlow}, which is initialized in
 * {@link #initializeConnectionFlow()}.
 * </p>
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
     * Encapsulates the flow for establishing a connection, which can vary
     * depending on how things are configured.
     */
    private volatile ConnectionFlow connectionFlow;

    /**
     * While we're in the process of connecting, it's possible that we'll
     * receive a new message to write. This lock helps us synchronize and wait
     * for the connection to be established before writing the next message.
     */
    private final Object connectLock = new Object();

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
            LOG.debug("Recording statistics from ConnectionTracer and then swallowing it");
            clientConnection.recordBytesReceivedFromServer(this,
                    (ConnectionTracer) msg);
        } else if (isConnecting()) {
            LOG.debug(
                    "In the middle of connecting, forwarding message to connection flow: {}",
                    msg);
            this.connectionFlow.read(msg);
        } else {
            super.read(msg);
        }
    }

    @Override
    protected ConnectionState readHTTPInitial(HttpResponse httpResponse) {
        LOG.debug("Received raw response: {}", httpResponse);

        rememberCurrentRequest();
        rememberCurrentResponse(httpResponse);
        filterResponseIfNecessary(httpResponse);
        respondWith(httpResponse);

        return ProxyUtils.isChunked(httpResponse) ? AWAITING_CHUNK
                : AWAITING_INITIAL;
    }

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
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

    @Override
    void write(Object msg) {
        LOG.debug("Requested write of {}", msg);
        if (is(DISCONNECTED)) {
            if (msg instanceof HttpRequest) {
                LOG.debug("Current disconnected, connect and then write the message");
                connectAndWrite((HttpRequest) msg);
            } else {
                LOG.warn(
                        "Received something other than an HttpRequest while disconnected, this shouldn't happen: {}",
                        msg);
            }
        } else {
            synchronized (connectionFlow.getConnectLock()) {
                if (isConnecting()) {
                    LOG.debug("Attempted to write while still in the process of connecting.  Wait for connecting to finish.");
                    try {
                        connectionFlow.getConnectLock().wait(30000);
                    } catch (InterruptedException ie) {
                        LOG.warn("Interrupted while waiting for connect monitor");
                    }
                    if (is(DISCONNECTED)) {
                        LOG.debug("Connection failed while we were waiting for it, don't write");
                        return;
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

    /**
     * Respond to the client with the given {@link HttpObject}.
     * 
     * @param httpObject
     */
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
                .then(ConnectChannel);

        if (sslContext != null) {
            this.connectionFlow.then(EncryptChannel);
        }

        if (ProxyUtils.isCONNECT(initialRequest)) {
            if (clientConnection.shouldChain(initialRequest)) {
                // If we're chaining to another proxy, send over the CONNECT
                // request
                this.connectionFlow.then(HTTPCONNECTWithChainedProxy);
            }

            this.connectionFlow.then(StartTunneling)
                    .then(clientConnection.RespondCONNECTSuccessful)
                    .then(clientConnection.StartTunneling);
        }
    }

    /**
     * Opens the socket connection.
     */
    private ConnectionFlowStep ConnectChannel = new ConnectionFlowStep(this,
            CONNECTING) {
        @Override
        boolean shouldExecuteOnEventLoop() {
            return false;
        }

        @Override
        protected Future<?> execute() {
            Bootstrap cb = new Bootstrap().group(proxyServer
                    .getProxyToServerWorkerFor(transportProtocol));

            switch (transportProtocol) {
            case TCP:
                LOG.debug("Connecting to server with TCP");
                cb.channelFactory(new ChannelFactory<Channel>() {
                    @Override
                    public Channel newChannel() {
                        return new NioSocketChannel();
                    }
                });
                break;
            case UDT:
                LOG.debug("Connecting to server with UDT");
                cb.channelFactory(NioUdtProvider.BYTE_CONNECTOR)
                        .option(ChannelOption.SO_REUSEADDR, true);
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

    /**
     * Encrypts the channel.
     */
    private ConnectionFlowStep EncryptChannel = new ConnectionFlowStep(this,
            HANDSHAKING) {
        protected Future<?> execute() {
            return encrypt();
        }
    };

    /**
     * Writes the HTTP CONNECT to the server and waits for a 200 response.
     */
    private ConnectionFlowStep HTTPCONNECTWithChainedProxy = new ConnectionFlowStep(
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
                flow.advance();
            } else {
                flow.fail();
            }
        }
    };

    /**
     * Try connecting to a new address, using a new set of connection
     * parameters.
     * 
     * @param newAddress
     * @param transportProtocol
     * @param sslContext
     * @param chainedProxyHostAndPort
     * @param initialRequest
     */
    protected void retryConnecting(InetSocketAddress newAddress,
            TransportProtocol transportProtocol,
            SSLContext sslContext,
            String chainedProxyHostAndPort,
            HttpRequest initialRequest) {
        this.address = newAddress;
        this.transportProtocol = transportProtocol;
        this.sslContext = sslContext;
        this.chainedProxyHostAndPort = chainedProxyHostAndPort;
        this.connectAndWrite(initialRequest);
    }

    /**
     * Initialize our {@link ChannelPipeline}.
     * 
     * @param pipeline
     * @param httpRequest
     */
    private void initChannelPipeline(ChannelPipeline pipeline,
            HttpRequest httpRequest) {
        pipeline.addLast("decoder", new ProxyHttpResponseDecoder(8192,
                8192 * 2,
                8192 * 2));

        // We decompress and aggregate chunks for responses from
        // sites we're applying filtering rules to.
        // TODO: Ox - it might be nice to move this responsibility into the
        // filters
        // themselves so that a filter can choose to operate more in streaming
        // fashion if it doesn't need the memory overhead of aggregating chunks.
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
                writeTimeoutSeconds = 0;
            }
            pipeline.addLast("idle", new IdleStateHandler(readTimeoutSeconds,
                    writeTimeoutSeconds, 0));
        }

        pipeline.addLast("handler", this);
    }

    /**
     * <p>
     * Do all the stuff that needs to be done after our {@link ConnectionFlow}
     * has succeeded.
     * </p>
     * 
     * @param shouldForwardInitialRequest
     *            whether or not we should forward the initial HttpRequest to
     *            the server after the connection has been established.
     */
    void connectionSucceeded(boolean shouldForwardInitialRequest) {
        become(AWAITING_INITIAL);
        clientConnection.serverConnectionSucceeded(this,
                shouldForwardInitialRequest);

        if (shouldForwardInitialRequest) {
            LOG.debug("Writing initial request");
            write(initialRequest);
        } else {
            LOG.debug("Dropping initial request");
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
