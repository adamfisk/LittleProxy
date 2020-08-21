package org.littleshoot.proxy.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.*;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.Future;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.proxy.*;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.littleshoot.proxy.impl.ConnectionState.*;

/**
 * <p>
 * Represents a connection from a client to our proxy. Each
 * ClientToProxyConnection can have multiple {@link ProxyToServerConnection}s,
 * at most one per outbound host:port.
 * </p>
 *
 * <p>
 * Once a ProxyToServerConnection has been created for a given server, it is
 * continually reused. The ProxyToServerConnection goes through its own
 * lifecycle of connects and disconnects, with different underlying
 * {@link Channel}s, but only a single ProxyToServerConnection object is used
 * per server. The one exception to this is CONNECT tunneling - if a connection
 * has been used for CONNECT tunneling, that connection will never be reused.
 * </p>
 *
 * <p>
 * As the ProxyToServerConnections receive responses from their servers, they
 * feed these back to the client by calling
 * {@link #respond(ProxyToServerConnection, HttpFilters, HttpRequest, HttpResponse, HttpObject)}
 * .
 * </p>
 */
public class ClientToProxyConnection extends ProxyConnection<HttpRequest> {
    private static final HttpResponseStatus CONNECTION_ESTABLISHED = new HttpResponseStatus(
            200, "Connection established");
    /**
     * Used for case-insensitive comparisons when parsing Connection header values.
     */
    private static final String LOWERCASE_TRANSFER_ENCODING_HEADER = HttpHeaderNames.TRANSFER_ENCODING.toString().toLowerCase(Locale.US);

    /**
     * Used for case-insensitive comparisons when checking direct proxy request.
     */
    private static final Pattern HTTP_SCHEME = Pattern.compile("^http://.*", Pattern.CASE_INSENSITIVE);

    /**
     * Keep track of all ProxyToServerConnections by host+port.
     */
    private final Map<String, ProxyToServerConnection> serverConnectionsByHostAndPort = new ConcurrentHashMap<String, ProxyToServerConnection>();

    /**
     * Keep track of how many servers are currently in the process of
     * connecting.
     */
    private final AtomicInteger numberOfCurrentlyConnectingServers = new AtomicInteger(
            0);

    /**
     * Keep track of how many servers are currently connected.
     */
    private final AtomicInteger numberOfCurrentlyConnectedServers = new AtomicInteger(
            0);

    /**
     * Keep track of how many times we were able to reuse a connection.
     */
    private final AtomicInteger numberOfReusedServerConnections = new AtomicInteger(
            0);

    /**
     * This is the current server connection that we're using while transferring
     * chunked data.
     */
    private volatile ProxyToServerConnection currentServerConnection;

    /**
     * The current filters to apply to incoming requests/chunks.
     */
    private volatile HttpFilters currentFilters = HttpFiltersAdapter.NOOP_FILTER;

    private volatile SSLSession clientSslSession;

    /**
     * Tracks whether or not this ClientToProxyConnection is current doing MITM.
     */
    private volatile boolean mitming = false;

    private AtomicBoolean authenticated = new AtomicBoolean();

    private final GlobalTrafficShapingHandler globalTrafficShapingHandler;

    /**
     * The current HTTP request that this connection is currently servicing.
     */
    private volatile HttpRequest currentRequest;

    ClientToProxyConnection(
            final DefaultHttpProxyServer proxyServer,
            SslEngineSource sslEngineSource,
            boolean authenticateClients,
            ChannelPipeline pipeline,
            GlobalTrafficShapingHandler globalTrafficShapingHandler) {
        super(AWAITING_INITIAL, proxyServer, false);

        initChannelPipeline(pipeline);

        if (sslEngineSource != null) {
            LOG.debug("Enabling encryption of traffic from client to proxy");
            encrypt(pipeline, sslEngineSource.newSslEngine(),
                    authenticateClients)
                    .addListener(
                            future -> {
                                if (future.isDone() && future.isSuccess()) {
                                    clientSslSession = sslEngine
                                            .getSession();
                                    recordClientSSLHandshakeSucceeded();
                                }
                            });
        }
        this.globalTrafficShapingHandler = globalTrafficShapingHandler;

        LOG.debug("Created ClientToProxyConnection");
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected ConnectionState readHTTPInitial(HttpRequest httpRequest) {
        LOG.debug("Received raw request: {}", httpRequest);

        // if we cannot parse the request, immediately return a 400 and close the connection, since we do not know what state
        // the client thinks the connection is in
        if (httpRequest.decoderResult().isFailure()) {
            LOG.debug("Could not parse request from client. Decoder result: {}", httpRequest.decoderResult().toString());

            FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    HttpResponseStatus.BAD_REQUEST,
                    "Unable to parse HTTP request");
            HttpUtil.setKeepAlive(response, false);

            respondWithShortCircuitResponse(response);

            return DISCONNECT_REQUESTED;
        }

        boolean authenticationRequired = authenticationRequired(httpRequest);

        if (authenticationRequired) {
            LOG.debug("Not authenticated!!");
            return AWAITING_PROXY_AUTHENTICATION;
        } else {
            return doReadHTTPInitial(httpRequest);
        }
    }

    /**
     * <p>
     * Reads an {@link HttpRequest}.
     * </p>
     *
     * <p>
     * If we don't yet have a {@link ProxyToServerConnection} for the desired
     * server, this takes care of creating it.
     * </p>
     *
     * <p>
     * Note - the "server" could be a chained proxy, not the final endpoint for
     * the request.
     * </p>
     *
     * @param httpRequest
     * @return
     */
    private ConnectionState doReadHTTPInitial(HttpRequest httpRequest) {
        // Make a copy of the original request
        this.currentRequest = copy(httpRequest);

        // Set up our filters based on the original request. If the HttpFiltersSource returns null (meaning the request/response
        // should not be filtered), fall back to the default no-op filter source.
        HttpFilters filterInstance = proxyServer.getFiltersSource().filterRequest(currentRequest, ctx);
        if (filterInstance != null) {
            currentFilters = filterInstance;
        } else {
            currentFilters = HttpFiltersAdapter.NOOP_FILTER;
        }

        // Send the request through the clientToProxyRequest filter, and respond with the short-circuit response if required
        HttpResponse clientToProxyFilterResponse = currentFilters.clientToProxyRequest(httpRequest);

        if (clientToProxyFilterResponse != null) {
            LOG.debug("Responding to client with short-circuit response from filter: {}", clientToProxyFilterResponse);

            boolean keepAlive = respondWithShortCircuitResponse(clientToProxyFilterResponse);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        // if origin-form requests are not explicitly enabled, short-circuit requests that treat the proxy as the
        // origin server, to avoid infinite loops
        if (!proxyServer.isAllowRequestsToOriginServer() && isRequestToOriginServer(httpRequest)) {
            boolean keepAlive = writeBadRequest(httpRequest);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        // Identify our server and chained proxy
        String serverHostAndPort = identifyHostAndPort(httpRequest);

        LOG.debug("Ensuring that hostAndPort are available in {}",
                httpRequest.uri());
        if (serverHostAndPort == null || StringUtils.isBlank(serverHostAndPort)) {
            LOG.warn("No host and port found in {}", httpRequest.uri());
            boolean keepAlive = writeBadGateway(httpRequest);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        LOG.debug("Finding ProxyToServerConnection for: {}", serverHostAndPort);
        currentServerConnection = isMitming() || isTunneling() ?
                this.currentServerConnection
                : this.serverConnectionsByHostAndPort.get(serverHostAndPort);

        boolean newConnectionRequired = false;
        if (ProxyUtils.isCONNECT(httpRequest)) {
            LOG.debug(
                    "Not reusing existing ProxyToServerConnection because request is a CONNECT for: {}",
                    serverHostAndPort);
            newConnectionRequired = true;
        } else if (currentServerConnection == null) {
            LOG.debug("Didn't find existing ProxyToServerConnection for: {}",
                    serverHostAndPort);
            newConnectionRequired = true;
        }

        if (newConnectionRequired) {
            try {
                currentServerConnection = ProxyToServerConnection.create(
                        proxyServer,
                        this,
                        serverHostAndPort,
                        currentFilters,
                        httpRequest,
                        globalTrafficShapingHandler);
                if (currentServerConnection == null) {
                    LOG.debug("Unable to create server connection, probably no chained proxies available");
                    boolean keepAlive = writeBadGateway(httpRequest);
                    resumeReading();
                    if (keepAlive) {
                        return AWAITING_INITIAL;
                    } else {
                        return DISCONNECT_REQUESTED;
                    }
                }
                // Remember the connection for later
                serverConnectionsByHostAndPort.put(serverHostAndPort,
                        currentServerConnection);
            } catch (UnknownHostException uhe) {
                LOG.info("Bad Host {}", httpRequest.uri());
                boolean keepAlive = writeBadGateway(httpRequest);
                resumeReading();
                if (keepAlive) {
                    return AWAITING_INITIAL;
                } else {
                    return DISCONNECT_REQUESTED;
                }
            }
        } else {
            LOG.debug("Reusing existing server connection: {}",
                    currentServerConnection);
            numberOfReusedServerConnections.incrementAndGet();
        }

        modifyRequestHeadersToReflectProxying(httpRequest);

        HttpResponse proxyToServerFilterResponse = currentFilters.proxyToServerRequest(httpRequest);
        if (proxyToServerFilterResponse != null) {
            LOG.debug("Responding to client with short-circuit response from filter: {}", proxyToServerFilterResponse);

            boolean keepAlive = respondWithShortCircuitResponse(proxyToServerFilterResponse);
            if (keepAlive) {
                return AWAITING_INITIAL;
            } else {
                return DISCONNECT_REQUESTED;
            }
        }

        LOG.debug("Writing request to ProxyToServerConnection");
        currentServerConnection.write(httpRequest, currentFilters);

        // Figure out our next state
        if (ProxyUtils.isCONNECT(httpRequest)) {
            return NEGOTIATING_CONNECT;
        } else if (ProxyUtils.isChunked(httpRequest)) {
            return AWAITING_CHUNK;
        } else {
            return AWAITING_INITIAL;
        }
    }

    /**
     * Returns true if the specified request is a request to an origin server, rather than to a proxy server. If this
     * request is being MITM'd, this method always returns false. The format of requests to a proxy server are defined
     * in RFC 7230, section 5.3.2 (all other requests are considered requests to an origin server):
     * <pre>
     * When making a request to a proxy, other than a CONNECT or server-wide
     * OPTIONS request (as detailed below), a client MUST send the target
     * URI in absolute-form as the request-target.
     * [...]
     * An example absolute-form of request-line would be:
     * GET http://www.example.org/pub/WWW/TheProject.html HTTP/1.1
     * To allow for transition to the absolute-form for all requests in some
     * future version of HTTP, a server MUST accept the absolute-form in
     * requests, even though HTTP/1.1 clients will only send them in
     * requests to proxies.
     * </pre>
     *
     * @param httpRequest the request to evaluate
     * @return true if the specified request is a request to an origin server, otherwise false
     */
    private boolean isRequestToOriginServer(HttpRequest httpRequest) {
        // while MITMing, all HTTPS requests are requests to the origin server, since the client does not know
        // the request is being MITM'd by the proxy
        if (httpRequest.method() == HttpMethod.CONNECT || isMitming()) {
            return false;
        }

        // direct requests to the proxy have the path only without a scheme
        String uri = httpRequest.uri();
        return !HTTP_SCHEME.matcher(uri).matches();
    }

    @Override
    protected void readHTTPContent(HttpContent chunk) {
        currentFilters.clientToProxyRequest(chunk);
        currentFilters.proxyToServerRequest(chunk);

        currentServerConnection.write(chunk);
    }

    @Override
    protected void readRaw(ByteBuf buf) {
        currentServerConnection.write(buf);
    }

    /***************************************************************************
     * Writing
     **************************************************************************/

    /**
     * Send a response to the client.
     *
     * @param serverConnection    the ProxyToServerConnection that's responding
     * @param filters             the filters to apply to the response
     * @param currentHttpRequest  the HttpRequest that prompted this response
     * @param currentHttpResponse the HttpResponse corresponding to this data (when doing
     *                            chunked transfers, this is the initial HttpResponse object
     *                            that came in before the other chunks)
     * @param httpObject          the data with which to respond
     */
    void respond(ProxyToServerConnection serverConnection, HttpFilters filters,
                 HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
                 HttpObject httpObject) {
        // we are sending a response to the client, so we are done handling this request
        this.currentRequest = null;

        httpObject = filters.serverToProxyResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }

        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;

            // if this HttpResponse does not have any means of signaling the end of the message body other than closing
            // the connection, convert the message to a "Transfer-Encoding: chunked" HTTP response. This avoids the need
            // to close the client connection to indicate the end of the message. (Responses to HEAD requests "must be" empty.)
            if (!ProxyUtils.isHEAD(currentHttpRequest) && !ProxyUtils.isResponseSelfTerminating(httpResponse)) {
                // if this is not a FullHttpResponse,  duplicate the HttpResponse from the server before sending it to
                // the client. this allows us to set the Transfer-Encoding to chunked without interfering with netty's
                // handling of the response from the server. if we modify the original HttpResponse from the server,
                // netty will not generate the appropriate LastHttpContent when it detects the connection closure from
                // the server (see HttpObjectDecoder#decodeLast). (This does not apply to FullHttpResponses, for which
                // netty already generates the empty final chunk when Transfer-Encoding is chunked.)
                if (!(httpResponse instanceof FullHttpResponse)) {
                    HttpResponse duplicateResponse = ProxyUtils.duplicateHttpResponse(httpResponse);

                    // set the httpObject and httpResponse to the duplicated response, to allow all other standard processing
                    // (filtering, header modification for proxying, etc.) to be applied.
                    httpObject = httpResponse = duplicateResponse;
                }

                HttpUtil.setTransferEncodingChunked(httpResponse, true);
            }

            fixHttpVersionHeaderIfNecessary(httpResponse);
            modifyResponseHeadersToReflectProxying(httpResponse);
        }

        httpObject = filters.proxyToClientResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }

        write(httpObject);

        if (ProxyUtils.isLastChunk(httpObject)) {
            writeEmptyBuffer();
        }

        closeConnectionsAfterWriteIfNecessary(serverConnection,
                currentHttpRequest, currentHttpResponse, httpObject);
    }

    /***************************************************************************
     * Connection Lifecycle
     **************************************************************************/

    /**
     * Tells the Client that its HTTP CONNECT request was successful.
     */
    ConnectionFlowStep RespondCONNECTSuccessful = new ConnectionFlowStep(
            this, NEGOTIATING_CONNECT) {
        @Override
        boolean shouldSuppressInitialRequest() {
            return true;
        }

        protected Future<?> execute() {
            LOG.debug("Responding with CONNECT successful");
            HttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                    CONNECTION_ESTABLISHED);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            ProxyUtils.addVia(response, proxyServer.getProxyAlias());
            return writeToChannel(response);
        }
    };

    /**
     * On connect of the client, start waiting for an initial
     * {@link HttpRequest}.
     */
    @Override
    protected void connected() {
        super.connected();
        become(AWAITING_INITIAL);
        recordClientConnected();
    }

    void timedOut(ProxyToServerConnection serverConnection) {
        if (currentServerConnection == serverConnection && this.lastReadTime > currentServerConnection.lastReadTime) {
            // the idle timeout fired on the active server connection. send a timeout response to the client.
            LOG.warn("Server timed out: {}", currentServerConnection);
            currentFilters.serverToProxyResponseTimedOut();
            writeGatewayTimeout(currentRequest);
        }
    }

    @Override
    protected void timedOut() {
        // idle timeout fired on the client channel. if we aren't waiting on a response from a server, hang up
        if (currentServerConnection == null || this.lastReadTime <= currentServerConnection.lastReadTime) {
            super.timedOut();
        }
    }

    /**
     * On disconnect of the client, disconnect all server connections.
     */
    @Override
    protected void disconnected() {
        super.disconnected();
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            serverConnection.disconnect();
        }
        recordClientDisconnected();
    }

    /**
     * Called when {@link ProxyToServerConnection} starts its connection flow.
     *
     * @param serverConnection
     */
    protected void serverConnectionFlowStarted(
            ProxyToServerConnection serverConnection) {
        stopReading();
        this.numberOfCurrentlyConnectingServers.incrementAndGet();
    }

    /**
     * If the {@link ProxyToServerConnection} completes its connection lifecycle
     * successfully, this method is called to let us know about it.
     *
     * @param serverConnection
     * @param shouldForwardInitialRequest
     */
    protected void serverConnectionSucceeded(
            ProxyToServerConnection serverConnection,
            boolean shouldForwardInitialRequest) {
        LOG.debug("Connection to server succeeded: {}",
                serverConnection.getRemoteAddress());
        resumeReadingIfNecessary();
        become(shouldForwardInitialRequest ? getCurrentState()
                : AWAITING_INITIAL);
        numberOfCurrentlyConnectedServers.incrementAndGet();
    }

    /**
     * If the {@link ProxyToServerConnection} fails to complete its connection
     * lifecycle successfully, this method is called to let us know about it.
     *
     * <p>
     * After failing to connect to the server, one of two things can happen:
     * </p>
     *
     * <ol>
     * <li>If the server was a chained proxy, we fall back to connecting to the
     * ultimate endpoint directly.</li>
     * <li>If the server was the ultimate endpoint, we return a 502 Bad Gateway
     * to the client.</li>
     * </ol>
     *
     * @param serverConnection
     * @param lastStateBeforeFailure
     * @param cause                  what caused the failure
     * @return true if we're falling back to a another chained proxy (or direct
     * connection) and trying again
     */
    protected boolean serverConnectionFailed(
            ProxyToServerConnection serverConnection,
            ConnectionState lastStateBeforeFailure,
            Throwable cause) {
        resumeReadingIfNecessary();
        HttpRequest initialRequest = serverConnection.getInitialRequest();
        try {
            boolean retrying = serverConnection.connectionFailed(cause);
            if (retrying) {
                LOG.debug("Failed to connect to upstream server or chained proxy. Retrying connection. Last state before failure: {}",
                        lastStateBeforeFailure, cause);
                return true;
            } else {
                LOG.debug(
                        "Connection to upstream server or chained proxy failed: {}.  Last state before failure: {}",
                        serverConnection.getRemoteAddress(),
                        lastStateBeforeFailure,
                        cause);
                connectionFailedUnrecoverably(initialRequest, serverConnection);
                return false;
            }
        } catch (UnknownHostException uhe) {
            connectionFailedUnrecoverably(initialRequest, serverConnection);
            return false;
        }
    }

    private void connectionFailedUnrecoverably(HttpRequest initialRequest, ProxyToServerConnection serverConnection) {
        // the connection to the server failed, so disconnect the server and remove the ProxyToServerConnection from the
        // map of open server connections
        serverConnection.disconnect();
        this.serverConnectionsByHostAndPort.remove(serverConnection.getServerHostAndPort());

        boolean keepAlive = writeBadGateway(initialRequest);
        if (keepAlive) {
            become(AWAITING_INITIAL);
        } else {
            become(DISCONNECT_REQUESTED);
        }
    }

    private void resumeReadingIfNecessary() {
        if (this.numberOfCurrentlyConnectingServers.decrementAndGet() == 0) {
            LOG.debug("All servers have finished attempting to connect, resuming reading from client.");
            resumeReading();
        }
    }

    /***************************************************************************
     * Other Lifecycle
     **************************************************************************/

    /**
     * On disconnect of the server, track that we have one fewer connected
     * servers and then disconnect the client if necessary.
     *
     * @param serverConnection
     */
    protected void serverDisconnected(ProxyToServerConnection serverConnection) {
        numberOfCurrentlyConnectedServers.decrementAndGet();

        // for non-SSL connections, do not disconnect the client from the proxy, even if this was the last server connection.
        // this allows clients to continue to use the open connection to the proxy to make future requests. for SSL
        // connections, whether we are tunneling or MITMing, we need to disconnect the client because there is always
        // exactly one ClientToProxyConnection per ProxyToServerConnection, and vice versa.
        if (isTunneling() || isMitming()) {
            disconnect();
        }
    }

    /**
     * When the ClientToProxyConnection becomes saturated, stop reading on all
     * associated ProxyToServerConnections.
     */
    @Override
    protected synchronized void becameSaturated() {
        super.becameSaturated();
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            synchronized (serverConnection) {
                if (this.isSaturated()) {
                    serverConnection.stopReading();
                }
            }
        }
    }

    /**
     * When the ClientToProxyConnection becomes writable, resume reading on all
     * associated ProxyToServerConnections.
     */
    @Override
    protected synchronized void becameWritable() {
        super.becameWritable();
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            synchronized (serverConnection) {
                if (!this.isSaturated()) {
                    serverConnection.resumeReading();
                }
            }
        }
    }

    /**
     * When a server becomes saturated, we stop reading from the client.
     *
     * @param serverConnection
     */
    protected synchronized void serverBecameSaturated(
            ProxyToServerConnection serverConnection) {
        if (serverConnection.isSaturated()) {
            LOG.info("Connection to server became saturated, stopping reading");
            stopReading();
        }
    }

    /**
     * When a server becomes writeable, we check to see if all servers are
     * writeable and if they are, we resume reading.
     */
    protected synchronized void serverBecameWriteable() {
        boolean anyServersSaturated = false;
        for (ProxyToServerConnection otherServerConnection : serverConnectionsByHostAndPort
                .values()) {
            if (otherServerConnection.isSaturated()) {
                anyServersSaturated = true;
                break;
            }
        }
        if (!anyServersSaturated) {
            LOG.info("All server connections writeable, resuming reading");
            resumeReading();
        }
    }

    @Override
    protected void exceptionCaught(Throwable cause) {
        try {
            if (cause instanceof IOException) {
                // IOExceptions are expected errors, for example when a browser is killed and aborts a connection.
                // rather than flood the logs with stack traces for these expected exceptions, we log the message at the
                // INFO level and the stack trace at the DEBUG level.
                LOG.info("An IOException occurred on ClientToProxyConnection: " + cause.getMessage());
                LOG.debug("An IOException occurred on ClientToProxyConnection", cause);
            } else if (cause instanceof RejectedExecutionException) {
                LOG.info("An executor rejected a read or write operation on the ClientToProxyConnection (this is normal if the proxy is shutting down). Message: " + cause.getMessage());
                LOG.debug("A RejectedExecutionException occurred on ClientToProxyConnection", cause);
            } else {
                LOG.error("Caught an exception on ClientToProxyConnection", cause);
            }
        } finally {
            // always disconnect the client when an exception occurs on the channel
            disconnect();
        }
    }

    /***************************************************************************
     * Connection Management
     **************************************************************************/

    /**
     * Initialize the {@link ChannelPipeline} for the client to proxy channel.
     * LittleProxy acts like a server here.
     * <p>
     * A {@link ChannelPipeline} invokes the read (Inbound) handlers in
     * ascending ordering of the list and then the write (Outbound) handlers in
     * descending ordering.
     * <p>
     * Regarding the Javadoc of {@link HttpObjectAggregator} it's needed to have
     * the {@link HttpResponseEncoder} or {@link io.netty.handler.codec.http.HttpRequestEncoder} before the
     * {@link HttpObjectAggregator} in the {@link ChannelPipeline}.
     *
     * @param pipeline
     */
    private void initChannelPipeline(ChannelPipeline pipeline) {
        LOG.debug("Configuring ChannelPipeline");

        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);

        pipeline.addLast("encoder", new HttpResponseEncoder());
        // We want to allow longer request lines, headers, and chunks
        // respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder(
                proxyServer.getMaxInitialLineLength(),
                proxyServer.getMaxHeaderSize(),
                proxyServer.getMaxChunkSize()));

        // Enable aggregation for filtering if necessary
        int numberOfBytesToBuffer = proxyServer.getFiltersSource()
                .getMaximumRequestBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }

        pipeline.addLast("requestReadMonitor", requestReadMonitor);
        pipeline.addLast("responseWrittenMonitor", responseWrittenMonitor);

        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, proxyServer
                        .getIdleConnectionTimeout()));

        pipeline.addLast("handler", this);
    }

    /**
     * This method takes care of closing client to proxy and/or proxy to server
     * connections after finishing a write.
     */
    private void closeConnectionsAfterWriteIfNecessary(
            ProxyToServerConnection serverConnection,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject) {
        boolean closeServerConnection = shouldCloseServerConnection(
                currentHttpRequest, currentHttpResponse, httpObject);
        boolean closeClientConnection = shouldCloseClientConnection(
                currentHttpRequest, currentHttpResponse, httpObject);

        if (closeServerConnection) {
            LOG.debug("Closing remote connection after writing to client");
            serverConnection.disconnect();
        }

        if (closeClientConnection) {
            LOG.debug("Closing connection to client after writes");
            disconnect();
        }
    }

    private void forceDisconnect(ProxyToServerConnection serverConnection) {
        LOG.debug("Forcing disconnect");
        serverConnection.disconnect();
        disconnect();
    }

    /**
     * Determine whether or not the client connection should be closed.
     *
     * @param req
     * @param res
     * @param httpObject
     * @return
     */
    private boolean shouldCloseClientConnection(HttpRequest req,
                                                HttpResponse res, HttpObject httpObject) {
        if (ProxyUtils.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (httpObject != null) {
                if (!ProxyUtils.isLastChunk(httpObject)) {
                    String uri = null;
                    if (req != null) {
                        uri = req.uri();
                    }
                    LOG.debug("Not closing client connection on middle chunk for {}", uri);
                    return false;
                } else {
                    LOG.debug("Handling last chunk. Using normal client connection closing rules.");
                }
            }
        }

        if (!HttpUtil.isKeepAlive(req)) {
            LOG.debug("Closing client connection since request is not keep alive: {}", req);
            // Here we simply want to close the connection because the
            // client itself has requested it be closed in the request.
            return true;
        }

        // ignore the response's keep-alive; we can keep this client connection open as long as the client allows it.

        LOG.debug("Not closing client connection for request: {}", req);
        return false;
    }

    /**
     * Determines if the remote connection should be closed based on the request
     * and response pair. If the request is HTTP 1.0 with no keep-alive header,
     * for example, the connection should be closed.
     * <p>
     * This in part determines if we should close the connection. Here's the
     * relevant section of RFC 2616:
     * <p>
     * "HTTP/1.1 defines the "close" connection option for the sender to signal
     * that the connection will be closed after completion of the response. For
     * example,
     * <p>
     * Connection: close
     * <p>
     * in either the request or the response header fields indicates that the
     * connection SHOULD NOT be considered `persistent' (section 8.1) after the
     * current request/response is complete."
     *
     * @param req The request.
     * @param res The response.
     * @param msg The message.
     * @return Returns true if the connection should close.
     */
    private boolean shouldCloseServerConnection(HttpRequest req,
                                                HttpResponse res, HttpObject msg) {
        if (ProxyUtils.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (msg != null) {
                if (!ProxyUtils.isLastChunk(msg)) {
                    String uri = null;
                    if (req != null) {
                        uri = req.uri();
                    }
                    LOG.debug("Not closing server connection on middle chunk for {}", uri);
                    return false;
                } else {
                    LOG.debug("Handling last chunk. Using normal server connection closing rules.");
                }
            }
        }

        // ignore the request's keep-alive; we can keep this server connection open as long as the server allows it.

        if (!HttpUtil.isKeepAlive(res)) {
            LOG.debug("Closing server connection since response is not keep alive: {}", res);
            // In this case, we want to honor the Connection: close header
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the client, however
            // as it's possible it has other connections open.
            return true;
        }

        LOG.debug("Not closing server connection for response: {}", res);
        return false;
    }

    /***************************************************************************
     * Authentication
     **************************************************************************/

    /**
     * <p>
     * Checks whether the given HttpRequest requires authentication.
     * </p>
     *
     * <p>
     * If the request contains credentials, these are checked.
     * </p>
     *
     * <p>
     * If authentication is still required, either because no credentials were
     * provided or the credentials were wrong, this writes a 407 response to the
     * client.
     * </p>
     *
     * @param request
     * @return
     */
    private boolean authenticationRequired(HttpRequest request) {

        if (authenticated.get()) {
            return false;
        }

        final ProxyAuthenticator authenticator = proxyServer
                .getProxyAuthenticator();

        if (authenticator == null)
            return false;

        if (!request.headers().contains(HttpHeaderNames.PROXY_AUTHORIZATION)) {
            writeAuthenticationRequired(authenticator.getRealm());
            return true;
        }

        List<String> values = request.headers().getAll(
                HttpHeaderNames.PROXY_AUTHORIZATION);
        String fullValue = values.iterator().next();
        String value = StringUtils.substringAfter(fullValue, "Basic ").trim();

        byte[] decodedValue = java.util.Base64.getDecoder().decode(value);

        String decodedString = new String(decodedValue, StandardCharsets.UTF_8);

        String userName = StringUtils.substringBefore(decodedString, ":");
        String password = StringUtils.substringAfter(decodedString, ":");
        if (!authenticator.authenticate(userName, password)) {
            writeAuthenticationRequired(authenticator.getRealm());
            return true;
        }

        LOG.debug("Got proxy authorization!");
        // We need to remove the header before sending the request on.
        String authentication = request.headers().get(
                HttpHeaderNames.PROXY_AUTHORIZATION);
        LOG.debug(authentication);
        request.headers().remove(HttpHeaderNames.PROXY_AUTHORIZATION);
        authenticated.set(true);
        return false;
    }

    private void writeAuthenticationRequired(String realm) {
        String body = "<!DOCTYPE HTML \"-//IETF//DTD HTML 2.0//EN\">\n"
                + "<html><head>\n"
                + "<title>407 Proxy Authentication Required</title>\n"
                + "</head><body>\n"
                + "<h1>Proxy Authentication Required</h1>\n"
                + "<p>This server could not verify that you\n"
                + "are authorized to access the document\n"
                + "requested.  Either you supplied the wrong\n"
                + "credentials (e.g., bad password), or your\n"
                + "browser doesn't understand how to supply\n"
                + "the credentials required.</p>\n" + "</body></html>\n";
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED, body);
        HttpHeaders.setDate(response, new Date());
        response.headers().set("Proxy-Authenticate",
                "Basic realm=\"" + (realm == null ? "Restricted Files" : realm) + "\"");
        write(response);
    }

    /***************************************************************************
     * Request/Response Rewriting
     **************************************************************************/

    /**
     * Copy the given {@link HttpRequest} verbatim.
     *
     * @param original
     * @return
     */
    private HttpRequest copy(HttpRequest original) {
        if (original instanceof FullHttpRequest) {
            return ((FullHttpRequest) original).copy();
        } else {
            HttpRequest request = new DefaultHttpRequest(original.getProtocolVersion(),
                    original.getMethod(), original.getUri());
            request.headers().set(original.headers());
            return request;
        }
    }

    /**
     * Chunked encoding is an HTTP 1.1 feature, but sometimes we get a chunked
     * response that reports its HTTP version as 1.0. In this case, we change it
     * to 1.1.
     *
     * @param httpResponse
     */
    private void fixHttpVersionHeaderIfNecessary(HttpResponse httpResponse) {
        String te = httpResponse.headers().get(
                HttpHeaderNames.TRANSFER_ENCODING);
        if (StringUtils.isNotBlank(te)
                && HttpHeaderValues.CHUNKED.contentEqualsIgnoreCase(te)) {
            if (httpResponse.protocolVersion() != HttpVersion.HTTP_1_1) {
                LOG.debug("Fixing HTTP version.");
                httpResponse.setProtocolVersion(HttpVersion.HTTP_1_1);
            }
        }
    }

    /**
     * If and only if our proxy is not running in transparent mode, modify the
     * request headers to reflect that it was proxied.
     *
     * @param httpRequest
     */
    private void modifyRequestHeadersToReflectProxying(HttpRequest httpRequest) {
        if (!currentServerConnection.hasUpstreamChainedProxy()) {
            /*
             * We are making the request to the origin server, so must modify
             * the 'absolute-URI' into the 'origin-form' as per RFC 7230
             * section 5.3.1.
             *
             * This must happen even for 'transparent' mode, otherwise the origin
             * server could infer that the request came via a proxy server.
             */
            LOG.debug("Modifying request for proxy chaining");
            // Strip host from uri
            String uri = httpRequest.getUri();
            String adjustedUri = ProxyUtils.stripHost(uri);
            LOG.debug("Stripped host from uri: {}    yielding: {}", uri,
                    adjustedUri);
            httpRequest.setUri(adjustedUri);
        }
        if (!proxyServer.isTransparent()) {
            LOG.debug("Modifying request headers for proxying");

            HttpHeaders headers = httpRequest.headers();

            // Remove sdch from encodings we accept since we can't decode it.
            ProxyUtils.removeSdchEncoding(headers);
            switchProxyConnectionHeader(headers);
            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            ProxyUtils.addVia(httpRequest, proxyServer.getProxyAlias());
        }
    }

    /**
     * If and only if our proxy is not running in transparent mode, modify the
     * response headers to reflect that it was proxied.
     *
     * @param httpResponse
     * @return
     */
    private void modifyResponseHeadersToReflectProxying(
            HttpResponse httpResponse) {
        if (!proxyServer.isTransparent()) {
            HttpHeaders headers = httpResponse.headers();

            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            ProxyUtils.addVia(httpResponse, proxyServer.getProxyAlias());

            /*
             * RFC2616 Section 14.18
             *
             * A received message that does not have a Date header field MUST be
             * assigned one by the recipient if the message will be cached by
             * that recipient or gatewayed via a protocol which requires a Date.
             */
            if (!headers.contains(HttpHeaderNames.DATE)) {
                HttpHeaders.setDate(httpResponse, new Date());
            }
        }
    }

    /**
     * Switch the de-facto standard "Proxy-Connection" header to "Connection"
     * when we pass it along to the remote host. This is largely undocumented
     * but seems to be what most browsers and servers expect.
     *
     * @param headers The headers to modify
     */
    private void switchProxyConnectionHeader(HttpHeaders headers) {
        String proxyConnectionKey = "Proxy-Connection";
        if (headers.contains(proxyConnectionKey)) {
            String header = headers.get(proxyConnectionKey);
            headers.remove(proxyConnectionKey);
            headers.set(HttpHeaderNames.CONNECTION, header);
        }
    }

    /**
     * RFC2616 Section 14.10
     * <p>
     * HTTP/1.1 proxies MUST parse the Connection header field before a message
     * is forwarded and, for each connection-token in this field, remove any
     * header field(s) from the message with the same name as the
     * connection-token.
     *
     * @param headers The headers to modify
     */
    private void stripConnectionTokens(HttpHeaders headers) {
        if (headers.contains(HttpHeaderNames.CONNECTION)) {
            for (String headerValue : headers.getAll(HttpHeaderNames.CONNECTION)) {
                for (String connectionToken : ProxyUtils.splitCommaSeparatedHeaderValues(headerValue)) {
                    // do not strip out the Transfer-Encoding header if it is specified in the Connection header, since LittleProxy does not
                    // normally modify the Transfer-Encoding of the message.
                    if (!LOWERCASE_TRANSFER_ENCODING_HEADER.equals(connectionToken.toLowerCase(Locale.US))) {
                        headers.remove(connectionToken);
                    }
                }
            }
        }
    }

    /**
     * Removes all headers that should not be forwarded. See RFC 2616 13.5.1
     * End-to-end and Hop-by-hop Headers.
     *
     * @param headers The headers to modify
     */
    private void stripHopByHopHeaders(HttpHeaders headers) {
        Set<String> headerNames = headers.names();
        for (String headerName : headerNames) {
            if (ProxyUtils.shouldRemoveHopByHopHeader(headerName)) {
                headers.remove(headerName);
            }
        }
    }

    /***************************************************************************
     * Miscellaneous
     **************************************************************************/

    /**
     * Tells the client that something went wrong trying to proxy its request. If the Bad Gateway is a response to
     * an HTTP HEAD request, the response will contain no body, but the Content-Length header will be set to the
     * value it would have been if this 502 Bad Gateway were in response to a GET.
     *
     * @param httpRequest the HttpRequest that is resulting in the Bad Gateway response
     * @return true if the connection will be kept open, or false if it will be disconnected
     */
    private boolean writeBadGateway(HttpRequest httpRequest) {
        String body = "Bad Gateway: " + httpRequest.getUri();
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_GATEWAY, body);

        if (ProxyUtils.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }

        return respondWithShortCircuitResponse(response);
    }

    /**
     * Tells the client that the request was malformed or erroneous. If the Bad Request is a response to
     * an HTTP HEAD request, the response will contain no body, but the Content-Length header will be set to the
     * value it would have been if this Bad Request were in response to a GET.
     *
     * @return true if the connection will be kept open, or false if it will be disconnected
     */
    private boolean writeBadRequest(HttpRequest httpRequest) {
        String body = "Bad Request to URI: " + httpRequest.getUri();
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.BAD_REQUEST, body);

        if (ProxyUtils.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }

        return respondWithShortCircuitResponse(response);
    }

    /**
     * Tells the client that the connection to the server, or possibly to some intermediary service (such as DNS), timed out.
     * If the Gateway Timeout is a response to an HTTP HEAD request, the response will contain no body, but the
     * Content-Length header will be set to the value it would have been if this 504 Gateway Timeout were in response to a GET.
     *
     * @param httpRequest the HttpRequest that is resulting in the Gateway Timeout response
     * @return true if the connection will be kept open, or false if it will be disconnected
     */
    private boolean writeGatewayTimeout(HttpRequest httpRequest) {
        String body = "Gateway Timeout";
        FullHttpResponse response = ProxyUtils.createFullHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.GATEWAY_TIMEOUT, body);

        if (httpRequest != null && ProxyUtils.isHEAD(httpRequest)) {
            // don't allow any body content in response to a HEAD request
            response.content().clear();
        }

        return respondWithShortCircuitResponse(response);
    }

    /**
     * Responds to the client with the specified "short-circuit" response. The response will be sent through the
     * {@link HttpFilters#proxyToClientResponse(HttpObject)} filter method before writing it to the client. The client
     * will not be disconnected, unless the response includes a "Connection: close" header, or the filter returns
     * a null HttpResponse (in which case no response will be written to the client and the connection will be
     * disconnected immediately). If the response is not a Bad Gateway or Gateway Timeout response, the response's headers
     * will be modified to reflect proxying, including adding a Via header, Date header, etc.
     *
     * @param httpResponse the response to return to the client
     * @return true if the connection will be kept open, or false if it will be disconnected.
     */
    private boolean respondWithShortCircuitResponse(HttpResponse httpResponse) {
        // we are sending a response to the client, so we are done handling this request
        this.currentRequest = null;

        HttpResponse filteredResponse = (HttpResponse) currentFilters.proxyToClientResponse(httpResponse);
        if (filteredResponse == null) {
            disconnect();
            return false;
        }

        // allow short-circuit messages to close the connection. normally the Connection header would be stripped when modifying
        // the message for proxying, so save the keep-alive status before the modifications are made.
        boolean isKeepAlive = HttpHeaders.isKeepAlive(httpResponse);

        // if the response is not a Bad Gateway or Gateway Timeout, modify the headers "as if" the short-circuit response were proxied
        int statusCode = httpResponse.getStatus().code();
        if (statusCode != HttpResponseStatus.BAD_GATEWAY.code() && statusCode != HttpResponseStatus.GATEWAY_TIMEOUT.code()) {
            modifyResponseHeadersToReflectProxying(httpResponse);
        }

        // restore the keep alive status, if it was overwritten when modifying headers for proxying
        HttpHeaders.setKeepAlive(httpResponse, isKeepAlive);

        write(httpResponse);

        if (ProxyUtils.isLastChunk(httpResponse)) {
            writeEmptyBuffer();
        }

        if (!HttpHeaders.isKeepAlive(httpResponse)) {
            disconnect();
            return false;
        }

        return true;
    }

    /**
     * Identify the host and port for a request.
     *
     * @param httpRequest
     * @return
     */
    private String identifyHostAndPort(HttpRequest httpRequest) {
        String hostAndPort = ProxyUtils.parseHostAndPort(httpRequest);
        if (StringUtils.isBlank(hostAndPort)) {
            List<String> hosts = httpRequest.headers().getAll(
                    HttpHeaderNames.HOST);
            if (hosts != null && !hosts.isEmpty()) {
                hostAndPort = hosts.get(0);
            }
        }

        return hostAndPort;
    }

    /**
     * Write an empty buffer at the end of a chunked transfer. We need to do
     * this to handle the way Netty creates HttpChunks from responses that
     * aren't in fact chunked from the remote server using Transfer-Encoding:
     * chunked. Netty turns these into pseudo-chunked responses in cases where
     * the response would otherwise fill up too much memory or where the length
     * of the response body is unknown. This is handy because it means we can
     * start streaming response bodies back to the client without reading the
     * entire response. The problem is that in these pseudo-cases the last chunk
     * is encoded to null, and this thwarts normal ChannelFutures from
     * propagating operationComplete events on writes to appropriate channel
     * listeners. We work around this by writing an empty buffer in those cases
     * and using the empty buffer's future instead to handle any operations we
     * need to when responses are fully written back to clients.
     */
    private void writeEmptyBuffer() {
        write(Unpooled.EMPTY_BUFFER);
    }

    public boolean isMitming() {
        return mitming;
    }

    protected void setMitming(boolean isMitming) {
        this.mitming = isMitming;
    }

    /***************************************************************************
     * Activity Tracking/Statistics
     *
     * We track statistics on bytes, requests and responses by adding handlers
     * at the appropriate parts of the pipeline (see initChannelPipeline()).
     **************************************************************************/
    private final BytesReadMonitor bytesReadMonitor = new BytesReadMonitor() {
        @Override
        protected void bytesRead(int numberOfBytes) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesReceivedFromClient(flowContext, numberOfBytes);
            }
        }
    };

    private RequestReadMonitor requestReadMonitor = new RequestReadMonitor() {
        @Override
        protected void requestRead(HttpRequest httpRequest) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.requestReceivedFromClient(flowContext, httpRequest);
            }
        }
    };

    private BytesWrittenMonitor bytesWrittenMonitor = new BytesWrittenMonitor() {
        @Override
        protected void bytesWritten(int numberOfBytes) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.bytesSentToClient(flowContext, numberOfBytes);
            }
        }
    };

    private ResponseWrittenMonitor responseWrittenMonitor = new ResponseWrittenMonitor() {
        @Override
        protected void responseWritten(HttpResponse httpResponse) {
            FlowContext flowContext = flowContext();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.responseSentToClient(flowContext,
                        httpResponse);
            }
        }
    };

    private void recordClientConnected() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.clientConnected(clientAddress);
            }
        } catch (Exception e) {
            LOG.error("Unable to recordClientConnected", e);
        }
    }

    private void recordClientSSLHandshakeSucceeded() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.clientSSLHandshakeSucceeded(
                        clientAddress, clientSslSession);
            }
        } catch (Exception e) {
            LOG.error("Unable to recorClientSSLHandshakeSucceeded", e);
        }
    }

    private void recordClientDisconnected() {
        try {
            InetSocketAddress clientAddress = getClientAddress();
            for (ActivityTracker tracker : proxyServer
                    .getActivityTrackers()) {
                tracker.clientDisconnected(
                        clientAddress, clientSslSession);
            }
        } catch (Exception e) {
            LOG.error("Unable to recordClientDisconnected", e);
        }
    }

    public InetSocketAddress getClientAddress() {
        if (channel == null) {
            return null;
        }
        return (InetSocketAddress) channel.remoteAddress();
    }

    private FlowContext flowContext() {
        if (currentServerConnection != null) {
            return new FullFlowContext(this, currentServerConnection);
        } else {
            return new FlowContext(this);
        }
    }

}
