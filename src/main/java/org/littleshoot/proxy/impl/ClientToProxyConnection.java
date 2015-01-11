package org.littleshoot.proxy.impl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.FullFlowContext;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SslEngineSource;

import javax.net.ssl.SSLSession;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_CHUNK;
import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_INITIAL;
import static org.littleshoot.proxy.impl.ConnectionState.AWAITING_PROXY_AUTHENTICATION;
import static org.littleshoot.proxy.impl.ConnectionState.DISCONNECT_REQUESTED;
import static org.littleshoot.proxy.impl.ConnectionState.NEGOTIATING_CONNECT;

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
            200, "HTTP/1.1 200 Connection established");

    private static final Set<String> HOP_BY_HOP_HEADERS = new HashSet<String>(
            Arrays.asList(new String[] { "connection", "keep-alive",
                    "proxy-authenticate", "proxy-authorization", "te",
                    "trailers", "upgrade" }));

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
    private volatile HttpFilters currentFilters;

    private volatile SSLSession clientSslSession;

    /**
     * Tracks whether or not this ClientToProxyConnection is current doing MITM.
     */
    private volatile boolean mitming = false;

    private AtomicBoolean authenticated = new AtomicBoolean();

    private final GlobalTrafficShapingHandler globalTrafficShapingHandler;

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
                            new GenericFutureListener<Future<? super Channel>>() {
                                @Override
                                public void operationComplete(
                                        Future<? super Channel> future)
                                        throws Exception {
                                    if (future.isSuccess()) {
                                        clientSslSession = sslEngine
                                                .getSession();
                                        recordClientSSLHandshakeSucceeded();
                                    }
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
        LOG.debug("Got request: {}", httpRequest);

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
        HttpRequest originalRequest = copy(httpRequest);

        // Set up our filters based on the original request
        currentFilters = proxyServer.getFiltersSource().filterRequest(
                originalRequest, ctx);

        // Do the pre filtering
        if (shortCircuitRespond(currentFilters
                .clientToProxyRequest(httpRequest))) {
            return DISCONNECT_REQUESTED;
        }

        // Identify our server and chained proxy
        String serverHostAndPort = identifyHostAndPort(httpRequest);

        LOG.debug("Ensuring that hostAndPort are available in {}",
                httpRequest.getUri());
        if (serverHostAndPort == null || StringUtils.isBlank(serverHostAndPort)) {
            LOG.warn("No host and port found in {}", httpRequest.getUri());
            writeBadGateway(httpRequest);
            return DISCONNECT_REQUESTED;
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
                    writeBadGateway(httpRequest);
                    resumeReading();
                    return DISCONNECT_REQUESTED;
                }
                // Remember the connection for later
                serverConnectionsByHostAndPort.put(serverHostAndPort,
                        currentServerConnection);
            } catch (UnknownHostException uhe) {
                LOG.info("Bad Host {}", httpRequest.getUri());
                writeBadGateway(httpRequest);
                resumeReading();
                return DISCONNECT_REQUESTED;
            }
        } else {
            LOG.debug("Reusing existing server connection: {}",
                    currentServerConnection);
            numberOfReusedServerConnections.incrementAndGet();
        }

        modifyRequestHeadersToReflectProxying(httpRequest);
        if (shortCircuitRespond(currentFilters
                .proxyToServerRequest(httpRequest))) {
            return DISCONNECT_REQUESTED;
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

    @Override
    protected void readHTTPChunk(HttpContent chunk) {
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
     * @param serverConnection
     *            the ProxyToServerConnection that's responding
     * @param filters
     *            the filters to apply to the response
     * @param currentHttpRequest
     *            the HttpRequest that prompted this response
     * @param currentHttpResponse
     *            the HttpResponse corresponding to this data (when doing
     *            chunked transfers, this is the initial HttpResponse object
     *            that came in before the other chunks)
     * @param httpObject
     *            the data with which to respond
     */
    void respond(ProxyToServerConnection serverConnection, HttpFilters filters,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject) {
        httpObject = filters.serverToProxyResponse(httpObject);
        if (httpObject == null) {
            forceDisconnect(serverConnection);
            return;
        }

        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;
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

    /**
     * Used for filtering. If a request filter returned a response, we short
     * circuit processing by sending the response to the client and
     * disconnecting.
     * 
     * @param shortCircuitResponse
     * @return
     */
    private boolean shortCircuitRespond(HttpResponse shortCircuitResponse) {
        if (shortCircuitResponse != null) {
            write(shortCircuitResponse);
            disconnect();
            return true;
        } else {
            return false;
        }
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
            HttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                    CONNECTION_ESTABLISHED);
            response.headers().set("Connection", "Keep-Alive");
            response.headers().set("Proxy-Connection", "Keep-Alive");
            ProxyUtils.addVia(response);
            return writeToChannel(response);
        };
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

    @Override
    protected void timedOut() {
        boolean clientReadMoreRecentlyThanServer =
                currentServerConnection == null
                        || this.lastReadTime > currentServerConnection.lastReadTime;
        if (clientReadMoreRecentlyThanServer) {
            LOG.debug("Server timed out: {}", currentServerConnection);
            writeGatewayTimeout();
        }
        super.timedOut();
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
     * @param cause
     *            what caused the failure
     * 
     * @return true if we're falling back to a another chained proxy (or direct
     *         connection) and trying again
     */
    protected boolean serverConnectionFailed(
            ProxyToServerConnection serverConnection,
            ConnectionState lastStateBeforeFailure,
            Throwable cause) {
        resumeReadingIfNecessary();
        HttpRequest initialRequest = serverConnection.getInitialRequest();
        try {
            if (serverConnection.connectionFailed(cause)) {
                LOG.info(
                        "Failed to connect via chained proxy, falling back to next chained proxy. Last state before failure: {}",
                        lastStateBeforeFailure, cause);
                return true;
            } else {
                LOG.debug(
                        "Connection to server failed: {}.  Last state before failure: {}",
                        serverConnection.getRemoteAddress(),
                        lastStateBeforeFailure,
                        cause);
                connectionFailedUnrecoverably(initialRequest);
                return false;
            }
        } catch (UnknownHostException uhe) {
            connectionFailedUnrecoverably(initialRequest);
            return false;
        }
    }

    private void connectionFailedUnrecoverably(HttpRequest initialRequest) {
        writeBadGateway(initialRequest);
        become(DISCONNECT_REQUESTED);
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
        disconnectClientIfNecessary();
    }

    /**
     * When the ClientToProxyConnection becomes saturated, stop reading on all
     * associated ProxyToServerConnections.
     */
    @Override
    synchronized protected void becameSaturated() {
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
    synchronized protected void becameWritable() {
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
    synchronized protected void serverBecameSaturated(
            ProxyToServerConnection serverConnection) {
        if (serverConnection.isSaturated()) {
            LOG.info("Connection to server became saturated, stopping reading");
            stopReading();
        }
    }

    /**
     * When a server becomes writeable, we check to see if all servers are
     * writeable and if they are, we resume reading.
     * 
     * @param serverConnection
     */
    synchronized protected void serverBecameWriteable(
            ProxyToServerConnection serverConnection) {
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
        String message = "Caught an exception on ClientToProxyConnection";
        boolean shouldWarn = cause instanceof ClosedChannelException ||
                cause.getMessage().contains("Connection reset by peer");
        if (shouldWarn) {
            LOG.warn(message, cause);
        } else {
            LOG.error(message, cause);
        }
        disconnect();
    }

    /***************************************************************************
     * Connection Management
     **************************************************************************/

    /**
     * Initialize the {@ChannelPipeline} for the client to
     * proxy channel.
     * 
     * @param pipeline
     */
    private void initChannelPipeline(ChannelPipeline pipeline) {
        LOG.debug("Configuring ChannelPipeline");

        pipeline.addLast("bytesReadMonitor", bytesReadMonitor);
        // We want to allow longer request lines, headers, and chunks
        // respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder(8192, 8192 * 2,
                8192 * 2));
        pipeline.addLast("requestReadMonitor", requestReadMonitor);

        // Enable aggregation for filtering if necessary
        int numberOfBytesToBuffer = proxyServer.getFiltersSource()
                .getMaximumRequestBufferSizeInBytes();
        if (numberOfBytesToBuffer > 0) {
            aggregateContentForFiltering(pipeline, numberOfBytesToBuffer);
        }

        pipeline.addLast("bytesWrittenMonitor", bytesWrittenMonitor);
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("responseWrittenMonitor", responseWrittenMonitor);

        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, proxyServer
                        .getIdleConnectionTimeout()));

        pipeline.addLast("handler", this);
    }

    /**
     * If all server connections have been disconnected, disconnect the client.
     */
    private void disconnectClientIfNecessary() {
        if (numberOfCurrentlyConnectedServers.get() == 0) {
            // All servers are disconnected, disconnect from client
            disconnect();
        }
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
                        uri = req.getUri();
                    }
                    LOG.debug("Not closing on middle chunk for {}", uri);
                    return false;
                } else {
                    LOG.debug("Last chunk... using normal closing rules");
                }
            }
        }

        if (!HttpHeaders.isKeepAlive(req)) {
            LOG.debug("Closing since request is not keep alive:");
            // Here we simply want to close the connection because the
            // client itself has requested it be closed in the request.
            return true;
        }
        LOG.debug("Not closing client to proxy connection for request: {}", req);
        return false;
    }

    /**
     * Determines if the remote connection should be closed based on the request
     * and response pair. If the request is HTTP 1.0 with no keep-alive header,
     * for example, the connection should be closed.
     * 
     * This in part determines if we should close the connection. Here's the
     * relevant section of RFC 2616:
     * 
     * "HTTP/1.1 defines the "close" connection option for the sender to signal
     * that the connection will be closed after completion of the response. For
     * example,
     * 
     * Connection: close
     * 
     * in either the request or the response header fields indicates that the
     * connection SHOULD NOT be considered `persistent' (section 8.1) after the
     * current request/response is complete."
     * 
     * @param req
     *            The request.
     * @param res
     *            The response.
     * @param msg
     *            The message.
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
                    LOG.debug("Not closing on middle chunk");
                    return false;
                } else {
                    LOG.debug("Last chunk...using normal closing rules");
                }
            }
        }
        if (!HttpHeaders.isKeepAlive(req)) {
            LOG.debug("Closing since request is not keep alive:{}, ", req);
            // Here we simply want to close the connection because the
            // client itself has requested it be closed in the request.
            return true;
        }
        if (!HttpHeaders.isKeepAlive(res)) {
            LOG.debug("Closing since response is not keep alive:{}", res);
            // In this case, we want to honor the Connection: close header
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the client, however
            // as it's possible it has other connections open.
            return true;
        }
        LOG.debug("Not closing -- response probably keep alive for:\n{}", res);
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

        if (!request.headers().contains(HttpHeaders.Names.PROXY_AUTHORIZATION)) {
            writeAuthenticationRequired();
            return true;
        }

        List<String> values = request.headers().getAll(
                HttpHeaders.Names.PROXY_AUTHORIZATION);
        String fullValue = values.iterator().next();
        String value = StringUtils.substringAfter(fullValue, "Basic ")
                .trim();
        byte[] decodedValue = Base64.decodeBase64(value);
        try {
            String decodedString = new String(decodedValue, "UTF-8");
            String userName = StringUtils.substringBefore(decodedString,
                    ":");
            String password = StringUtils.substringAfter(decodedString,
                    ":");
            if (!authenticator.authenticate(userName,
                    password)) {
                writeAuthenticationRequired();
                return true;
            }
        } catch (UnsupportedEncodingException e) {
            LOG.error("Could not decode?", e);
        }

        LOG.info("Got proxy authorization!");
        // We need to remove the header before sending the request on.
        String authentication = request.headers().get(
                HttpHeaders.Names.PROXY_AUTHORIZATION);
        LOG.info(authentication);
        request.headers().remove(HttpHeaders.Names.PROXY_AUTHORIZATION);
        authenticated.set(true);
        return false;
    }

    private void writeAuthenticationRequired() {
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
        DefaultFullHttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED, body);
        response.headers().set("Date", ProxyUtils.httpDate());
        response.headers().set("Proxy-Authenticate",
                "Basic realm=\"Restricted Files\"");
        response.headers().set("Date", ProxyUtils.httpDate());
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
        if (original instanceof DefaultFullHttpRequest) {
            ByteBuf content = ((DefaultFullHttpRequest) original).content();
            return new DefaultFullHttpRequest(original.getProtocolVersion(),
                    original.getMethod(), original.getUri(), content);
        } else {
            return new DefaultHttpRequest(original.getProtocolVersion(),
                    original.getMethod(), original.getUri());
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
                HttpHeaders.Names.TRANSFER_ENCODING);
        if (StringUtils.isNotBlank(te)
                && te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
            if (httpResponse.getProtocolVersion() != HttpVersion.HTTP_1_1) {
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
        if (!proxyServer.isTransparent()) {
            LOG.debug("Modifying request headers for proxying");

            if (!currentServerConnection.hasUpstreamChainedProxy()) {
                LOG.debug("Modifying request for proxy chaining");
                // Strip host from uri
                String uri = httpRequest.getUri();
                String adjustedUri = ProxyUtils.stripHost(uri);
                LOG.debug("Stripped host from uri: {}    yielding: {}", uri,
                        adjustedUri);
                httpRequest.setUri(adjustedUri);
            }

            HttpHeaders headers = httpRequest.headers();

            removeSDCHEncoding(headers);
            switchProxyConnectionHeader(headers);
            stripConnectionTokens(headers);
            stripHopByHopHeaders(headers);
            ProxyUtils.addVia(httpRequest);
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
            ProxyUtils.addVia(httpResponse);

            /*
             * RFC2616 Section 14.18
             * 
             * A received message that does not have a Date header field MUST be
             * assigned one by the recipient if the message will be cached by
             * that recipient or gatewayed via a protocol which requires a Date.
             */
            if (!headers.contains("Date")) {
                headers.set("Date", ProxyUtils.httpDate());
            }
        }
    }

    /**
     * Remove sdch from encodings we accept since we can't decode it.
     * 
     * @param headers
     *            The headers to modify
     */
    private void removeSDCHEncoding(HttpHeaders headers) {
        String ae = headers.get(HttpHeaders.Names.ACCEPT_ENCODING);
        if (StringUtils.isNotBlank(ae)) {
            //
            String noSdch = ae.replace(",sdch", "").replace("sdch", "");
            headers.set(HttpHeaders.Names.ACCEPT_ENCODING, noSdch);
            LOG.debug("Removed sdch and inserted: {}", noSdch);
        }
    }

    /**
     * Switch the de-facto standard "Proxy-Connection" header to "Connection"
     * when we pass it along to the remote host. This is largely undocumented
     * but seems to be what most browsers and servers expect.
     * 
     * @param headers
     *            The headers to modify
     */
    private void switchProxyConnectionHeader(HttpHeaders headers) {
        String proxyConnectionKey = "Proxy-Connection";
        if (headers.contains(proxyConnectionKey)) {
            String header = headers.get(proxyConnectionKey);
            headers.remove(proxyConnectionKey);
            headers.set("Connection", header);
        }
    }

    /**
     * RFC2616 Section 14.10
     * 
     * HTTP/1.1 proxies MUST parse the Connection header field before a message
     * is forwarded and, for each connection-token in this field, remove any
     * header field(s) from the message with the same name as the
     * connection-token.
     * 
     * @param headers
     *            The headers to modify
     */
    private void stripConnectionTokens(HttpHeaders headers) {
        if (headers.contains("Connection")) {
            for (String headerValue : headers.getAll("Connection")) {
                for (String connectionToken : headerValue.split(",")) {
                    headers.remove(connectionToken);
                }
            }
        }
    }

    /**
     * Removes all headers that should not be forwarded. See RFC 2616 13.5.1
     * End-to-end and Hop-by-hop Headers.
     * 
     * @param headers
     *            The headers to modify
     */
    private void stripHopByHopHeaders(HttpHeaders headers) {
        Set<String> headerNames = headers.names();
        for (String name : headerNames) {
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                headers.remove(name);
            }
        }
    }

    /***************************************************************************
     * Miscellaneous
     **************************************************************************/

    /**
     * Tells the client that something went wrong trying to proxy its request.
     * 
     * @param request
     */
    private void writeBadGateway(HttpRequest request) {
        String body = "Bad Gateway: " + request.getUri();
        DefaultFullHttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY, body);
        response.headers().set(HttpHeaders.Names.CONNECTION, "close");
        write(response);
        disconnect();
    }

    /**
     * Tells the client that the connection to the server timed out.
     */
    private void writeGatewayTimeout() {
        String body = "Gateway Timeout";
        DefaultFullHttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                HttpResponseStatus.GATEWAY_TIMEOUT, body);
        response.headers().set(HttpHeaders.Names.CONNECTION, "close");
        write(response);
    }

    /**
     * Factory for {@link DefaultFullHttpResponse}s.
     * 
     * @param httpVersion
     * @param status
     * @param body
     * @return
     */
    private DefaultFullHttpResponse responseFor(HttpVersion httpVersion,
            HttpResponseStatus status, String body) {
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        ByteBuf content = Unpooled.copiedBuffer(bytes);
        return responseFor(httpVersion, status, content, bytes.length);
    }

    /**
     * Factory for {@link DefaultFullHttpResponse}s.
     * 
     * @param httpVersion
     * @param status
     * @param body
     * @param contentLength
     * @return
     */
    private DefaultFullHttpResponse responseFor(HttpVersion httpVersion,
            HttpResponseStatus status, ByteBuf body, int contentLength) {
        DefaultFullHttpResponse response = body != null ? new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, body)
                : new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        if (body != null) {
            response.headers().set(HttpHeaders.Names.CONTENT_LENGTH,
                    contentLength);
            response.headers().set("Content-Type", "text/html; charset=UTF-8");
        }
        return response;
    }

    /**
     * Factory for {@link DefaultFullHttpResponse}s.
     * 
     * @param httpVersion
     * @param status
     * @return
     */
    private DefaultFullHttpResponse responseFor(HttpVersion httpVersion,
            HttpResponseStatus status) {
        return responseFor(httpVersion, status, (ByteBuf) null, 0);
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
                    HttpHeaders.Names.HOST);
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
