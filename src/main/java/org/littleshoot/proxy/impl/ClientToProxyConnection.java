package org.littleshoot.proxy.impl;

import static org.littleshoot.proxy.impl.ConnectionState.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.dnssec4j.VerifiedAddressFactory;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainProxyManager;
import org.littleshoot.proxy.FlowContext;
import org.littleshoot.proxy.HandshakeHandler;
import org.littleshoot.proxy.HandshakeHandlerFactory;
import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.HttpRequestFilter;
import org.littleshoot.proxy.HttpResponseFilters;
import org.littleshoot.proxy.ProxyAuthenticator;

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
 * {@link #respond(ProxyToServerConnection, HttpRequest, HttpResponse, HttpObject)}
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

    private final ChainProxyManager chainProxyManager;
    private final ProxyAuthenticator authenticator;
    private final HandshakeHandlerFactory handshakeHandlerFactory;
    private final HttpRequestFilter requestFilter;
    private final HttpResponseFilters responseFilters;
    private final Collection<ActivityTracker> activityTrackers;

    /**
     * Keep track of all ProxyToServerConnections by host+port.
     */
    private final Map<String, ProxyToServerConnection> serverConnectionsByHostAndPort = new ConcurrentHashMap<String, ProxyToServerConnection>();

    /**
     * This is the current server connection that we're using while transferring
     * chunked data.
     */
    private volatile ProxyToServerConnection currentServerConnection;

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
     * Keep track of requests which we've decided not to send via chained
     * proxies.
     */
    private final Map<HttpRequest, Boolean> requestsForWhichProxyChainingIsDisabled = new ConcurrentHashMap<HttpRequest, Boolean>();

    ClientToProxyConnection(EventLoopGroup proxyToServerWorkerPool,
            ChannelGroup channelGroup, ChainProxyManager chainProxyManager,
            ProxyAuthenticator authenticator,
            HandshakeHandlerFactory handshakeHandlerFactory,
            HttpRequestFilter requestFilter,
            HttpResponseFilters responseFilters,
            Collection<ActivityTracker> activityTrackers,
            ChannelPipeline pipeline) {
        super(AWAITING_INITIAL, proxyToServerWorkerPool, channelGroup);
        this.authenticator = authenticator;
        this.handshakeHandlerFactory = handshakeHandlerFactory;
        this.requestFilter = requestFilter;
        this.responseFilters = responseFilters;
        this.chainProxyManager = chainProxyManager;
        this.activityTrackers = activityTrackers;
        initChannelPipeline(pipeline);
        LOG.debug("Created ClientToProxyConnection");
    }

    /***************************************************************************
     * Reading
     **************************************************************************/

    @Override
    protected void read(Object msg) {
        if (msg instanceof ConnectionTracer) {
            // Record statistic for ConnectionTracer and then ignore it
            if (currentServerConnection != null) {
                recordBytesReceivedFromClient(currentServerConnection,
                        (ConnectionTracer) msg);
            }
        } else {
            // Process as usual
            super.read(msg);
        }
    }

    @Override
    protected ConnectionState readInitial(HttpRequest httpRequest) {
        LOG.debug("Got request: {}", httpRequest);
        boolean authenticationRequired = authenticationRequired(httpRequest);
        if (authenticationRequired) {
            LOG.debug("Not authenticated!!");
            return AWAITING_PROXY_AUTHENTICATION;
        } else {
            String serverHostAndPort = identifyHostAndPort(httpRequest);
            String chainedProxyHostAndPort = getChainProxyHostAndPort(httpRequest);
            String hostAndPort = chainedProxyHostAndPort != null ? chainedProxyHostAndPort
                    : serverHostAndPort;

            recordRequestReceivedFromClient(serverHostAndPort,
                    chainedProxyHostAndPort,
                    httpRequest);

            LOG.debug("Identifying server for: {}", hostAndPort);

            if (hostAndPort == null || StringUtils.isBlank(hostAndPort)) {
                LOG.warn("No host and port found in {}", httpRequest.getUri());
                writeBadGateway(httpRequest);
                return DISCONNECT_REQUESTED;
            }

            LOG.debug("Finding ProxyToServerConnection");
            currentServerConnection = this.serverConnectionsByHostAndPort
                    .get(hostAndPort);
            boolean newConnectionRequired = ProxyUtils.isCONNECT(httpRequest)
                    || currentServerConnection == null;
            if (newConnectionRequired) {
                if (currentServerConnection != null) {
                    LOG.debug("Not reusing existing connection because request is a CONNECT");
                }
                try {
                    currentServerConnection = connectToServer(httpRequest,
                            hostAndPort, serverHostAndPort,
                            chainedProxyHostAndPort);
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

            HttpRequest originalRequest = copy(httpRequest);
            modifyRequestHeadersToReflectProxying(httpRequest);
            filterRequestIfNecessary(httpRequest);

            LOG.debug("Writing request to ProxyToServerConnection");
            currentServerConnection.write(httpRequest, originalRequest);
            
            if (ProxyUtils.isCONNECT(httpRequest)) {
                return NEGOTIATING_CONNECT;
            } else if (ProxyUtils.isChunked(httpRequest)) {
                return AWAITING_CHUNK;
            } else {
                return AWAITING_INITIAL;
            }
        }
    }

    @Override
    protected void readChunk(HttpContent chunk) {
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
     * Respond to the client.
     * 
     * @param serverConnection
     *            the ProxyToServerConnection that's responding
     * @param currentHttpRequest
     *            the HttpRequest that prompted this response
     * @param currentHttpResponse
     *            the HttpResponse corresponding to this data (when doing
     *            chunked transfers, this is the initial HttpResponse object
     *            that came in before the other chunks)
     * @param httpObject
     *            the data with which to respond
     */
    void respond(ProxyToServerConnection serverConnection,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject) {
        if (httpObject instanceof HttpResponse) {
            HttpResponse httpResponse = (HttpResponse) httpObject;
            fixHttpVersionHeaderIfNecessary(httpResponse);
            modifyResponseHeadersToReflectProxying(httpResponse);
            // Record stats
            recordResponseReceivedFromServer(serverConnection, httpResponse);
        }

        write(httpObject);
        if (ProxyUtils.isLastChunk(httpObject)) {
            writeEmptyBuffer();
        }

        closeConnectionsAfterWriteIfNecessary(serverConnection,
                currentHttpRequest, currentHttpResponse, httpObject);
    }

    /***************************************************************************
     * Lifecycle
     **************************************************************************/

    /**
     * While we're in the process of connecting to a server, stop reading.
     * 
     * @param serverConnection
     */
    protected void connectingToServer(ProxyToServerConnection serverConnection) {
        stopReading();
        this.numberOfCurrentlyConnectingServers.incrementAndGet();
    }

    /**
     * Once all servers have connected, resume reading.
     * 
     * @param serverConnection
     *            the ProxyToServerConnection that connected
     * @param initialRequest
     *            the HttpRequest that prompted this connection
     * @param connectionSuccessful
     *            whether or not the attempt to connect was successful
     */
    protected void serverConnected(ProxyToServerConnection serverConnection,
            HttpRequest initialRequest,
            boolean connectionSuccessful) {
        LOG.debug("{} to server: {}",
                connectionSuccessful ? "Finished connecting"
                        : "Failed to connect", serverConnection.getAddress());
        if (connectionSuccessful) {
            if (ProxyUtils.isCONNECT(initialRequest)) {
                finishCONNECT(serverConnection, initialRequest);
            } else {
                recordServerConnectionResult(serverConnection, initialRequest,
                        connectionSuccessful);
            }
        } else {
            recordServerConnectionResult(serverConnection, initialRequest,
                    false);
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
    }

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
     * When the ClientToProxyConnection becomes writeable, resume reading on all
     * associated ProxyToServerConnections.
     */
    @Override
    synchronized protected void becameWriteable() {
        super.becameWriteable();
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
        if (cause instanceof ClosedChannelException) {
            LOG.warn(message, cause);
        } else {
            LOG.error(message, cause);
        }
        disconnect();
    }

    /***************************************************************************
     * Proxy Chaining
     **************************************************************************/

    /**
     * Determine whether the given request should be handled by a chained proxy.
     * 
     * @param httpRequest
     * @return
     */
    protected boolean shouldChain(HttpRequest httpRequest) {
        return getChainProxyHostAndPort(httpRequest) != null;
    }

    /**
     * Get the host and port for the chained proxy.
     * 
     * @param httpRequest
     * @return
     */
    protected String getChainProxyHostAndPort(HttpRequest httpRequest) {
        if (chainProxyManager == null) {
            return null;
        } else if (this.requestsForWhichProxyChainingIsDisabled
                .containsKey(httpRequest)) {
            return null;
        } else {
            return chainProxyManager.getChainProxy(httpRequest);
        }
    }

    /**
     * Disables proxy chaining for the given request.
     * 
     * @param request
     */
    protected void disableChainingFor(HttpRequest request) {
        this.requestsForWhichProxyChainingIsDisabled.put(request, true);
    }

    /***************************************************************************
     * Connection Management
     **************************************************************************/

    private ProxyToServerConnection connectToServer(HttpRequest request,
            String hostAndPort, String serverHostAndPort,
            String chainedProxyHostAndPort)
            throws UnknownHostException {
        LOG.debug("Establishing new ProxyToServerConnection");
        InetSocketAddress address = addressFor(hostAndPort);
        HttpFilter responseFilter = null;
        if (responseFilters != null) {
            responseFilter = responseFilters.getFilter(serverHostAndPort);
        }
        ProxyToServerConnection connection = new ProxyToServerConnection(
                proxyToServerWorkerPool, allChannels, this, address,
                serverHostAndPort, chainedProxyHostAndPort,
                responseFilter);
        serverConnectionsByHostAndPort.put(hostAndPort, connection);
        return connection;
    }

    private ChannelFuture respondCONNECTSuccessful() {
        LOG.debug("Responding with CONNECT successful");
        HttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                CONNECTION_ESTABLISHED);
        response.headers().set("Connection", "Keep-Alive");
        response.headers().set("Proxy-Connection", "Keep-Alive");
        ProxyUtils.addVia(response);
        return writeToChannel(response);
    }

    /**
     * <p>
     * Ends the flow for establishing a CONNECT tunnel. The handling is
     * different depending on whether we're doing a simple tunnel or acting as
     * MITM.
     * </p>
     * 
     * <p>
     * See {@link ProxyToServerConnection#startCONNECT()} for the beginning of
     * this flow.
     * </p>
     * 
     * @param serverConnection
     *            the ProxyToServerConnection that connected
     * @param initialRequest
     *            the HTTPRequest that prompted us to do a CONNECT
     */
    private void finishCONNECT(final ProxyToServerConnection serverConnection,
            final HttpRequest initialRequest) {
        LOG.debug("Handling CONNECT request");
        respondCONNECTSuccessful().addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future)
                    throws Exception {
                if (LittleProxyConfig.isUseSSLMitm()) {
                    finishCONNECTWithMITM(serverConnection, initialRequest);
                } else {
                    finishCONNECTWithTunneling(serverConnection, initialRequest);
                }
            }
        });
    }

    /**
     * <p>
     * Ends the flow for establishing a simple CONNECT tunnel.
     * </p>
     * 
     * <p>
     * See {@link ProxyToServerConnection#startCONNECTWithTunneling()} for the
     * beginning of this flow.
     * </p>
     * 
     * @param serverConnection
     *            the ProxyToServerConnection that connected
     * @param initialRequest
     *            the HttpRequest that prompted us to do a CONNECT
     */
    private void finishCONNECTWithTunneling(
            final ProxyToServerConnection serverConnection,
            final HttpRequest initialRequest) {
        LOG.debug("Finishing tunneling");
        startTunneling().addListener(new GenericFutureListener<Future<?>>() {
            @Override
            public void operationComplete(Future<?> future) throws Exception {
                recordServerConnectionResult(serverConnection, initialRequest,
                        future.isSuccess());
                if (future.isSuccess()) {
                    LOG.debug("Tunnel Established");
                }
            }
        });
    }

    /**
     * <p>
     * Ends the flow for establishing a man-in-the-middle tunnel.
     * </p>
     * 
     * <p>
     * See {@link ProxyToServerConnection#startCONNECTWithMITM()} for the
     * beginning of this flow.
     * </p>
     * 
     * @param serverConnection
     *            the ProxyToServerConnection that connected
     * @param initialRequest
     *            the HTTPRequest that prompted us to do a CONNECT
     */
    private void finishCONNECTWithMITM(
            final ProxyToServerConnection serverConnection,
            final HttpRequest initialRequest) {
        LOG.debug("Finishing SSL MITM");
        enableSSLAsServer().addListener(
                new GenericFutureListener<Future<? super Channel>>() {
                    @Override
                    public void operationComplete(Future<? super Channel> future)
                            throws Exception {
                        become(AWAITING_INITIAL);
                        recordServerConnectionResult(serverConnection,
                                initialRequest, future.isSuccess());
                        if (future.isSuccess()) {
                            LOG.debug("SSL MITM Established");
                        }
                    }
                });
    }

    /**
     * <p>
     * Record the result of traying to connect to a server. If we failed to
     * connect to the server, one of two things can happen:
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
     * @param initialRequest
     * @param connectionSuccessful
     */
    private void recordServerConnectionResult(
            ProxyToServerConnection serverConnection,
            HttpRequest initialRequest, boolean connectionSuccessful) {
        if (this.numberOfCurrentlyConnectingServers.decrementAndGet() == 0) {
            resumeReading();
        }
        if (connectionSuccessful) {
            LOG.debug("Connection to server succeeded: {}",
                    serverConnection.getAddress());
            numberOfCurrentlyConnectedServers.incrementAndGet();
        } else {
            if (shouldChain(initialRequest)) {
                fallbackToDirectConnection(serverConnection, initialRequest);
            } else {
                LOG.debug("Connection to server failed: {}",
                        serverConnection.getAddress());
                writeBadGateway(initialRequest);
            }
        }
    }

    /**
     * Falls back to making a direct connection for the given request, bypassing
     * the chained proxy.
     * 
     * @param serverConnection
     * @param initialRequest
     */
    private void fallbackToDirectConnection(
            ProxyToServerConnection serverConnection,
            HttpRequest initialRequest) {
        LOG.info("Failed to connect via chained proxy, falling back to direct connection");
        // If we failed to connect to a chained proxy, disable proxy
        // chaining for this request and try again
        disableChainingFor(initialRequest);
        String hostAndPort = identifyHostAndPort(initialRequest);
        try {
            serverConnection.retryConnecting(addressFor(hostAndPort),
                    initialRequest);
        } catch (UnknownHostException uhe) {
            LOG.info("Bad Host {}", initialRequest.getUri());
            writeBadGateway(initialRequest);
            become(DISCONNECT_REQUESTED);
        }
    }

    private void initChannelPipeline(ChannelPipeline pipeline) {
        LOG.debug("Configuring ChannelPipeline");

        if (this.handshakeHandlerFactory != null) {
            LOG.debug("Adding SSL handler");
            HandshakeHandler hh = this.handshakeHandlerFactory
                    .newHandshakeHandler();
            pipeline.addLast(hh.getId(), hh.getChannelHandler());
        }

        // We want to allow longer request lines, headers, and chunks
        // respectively.
        pipeline.addLast("decoder", new ProxyHttpRequestDecoder(8192, 8192 * 2,
                8192 * 2));
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast(
                "idle",
                new IdleStateHandler(0, 0, LittleProxyConfig
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

    private boolean shouldCloseClientConnection(HttpRequest req,
            HttpResponse res, HttpObject httpObject) {
        if (ProxyUtils.isChunked(res)) {
            // If the response is chunked, we want to return false unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (httpObject != null) {
                if (!ProxyUtils.isLastChunk(httpObject)) {
                    LOG.debug("Not closing on middle chunk for {}",
                            req.getUri());
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

    private boolean authenticationRequired(HttpRequest request) {
        if (!request.headers().contains(HttpHeaders.Names.PROXY_AUTHORIZATION)) {
            if (this.authenticator != null) {
                writeAuthenticationRequired();
                return true;
            }
            return false;
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
            if (!authenticator.authenticate(userName, password)) {
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
        return false;
    }

    private void writeAuthenticationRequired() {
        String body = "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n"
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

    private void filterRequestIfNecessary(HttpRequest httpRequest) {
        if (requestFilter != null) {
            requestFilter.filter(httpRequest);
        }
    }

    /**
     * If and only if our proxy is not running in transparent mode, modify the
     * request headers to reflect that it was proxied.
     * 
     * @param httpRequest
     */
    private void modifyRequestHeadersToReflectProxying(HttpRequest httpRequest) {
        if (!LittleProxyConfig.isTransparent()) {
            LOG.debug("Modifying request headers for proxying");

            if (!shouldChain(httpRequest)) {
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
        if (!LittleProxyConfig.isTransparent()) {
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

    private void writeBadGateway(HttpRequest request) {
        String body = "Bad Gateway: " + request.getUri();
        DefaultFullHttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY, body);
        response.headers().set(HttpHeaders.Names.CONNECTION, "close");
        write(response);
        disconnect();
    }

    private DefaultFullHttpResponse responseFor(HttpVersion httpVersion,
            HttpResponseStatus status, String body) {
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        ByteBuf content = Unpooled.copiedBuffer(bytes);
        return responseFor(httpVersion, status, content, bytes.length);
    }

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

    private InetSocketAddress addressFor(String hostAndPort)
            throws UnknownHostException {
        String host;
        int port;
        if (hostAndPort.contains(":")) {
            host = StringUtils.substringBefore(hostAndPort, ":");
            String portString = StringUtils.substringAfter(hostAndPort,
                    ":");
            port = Integer.parseInt(portString);
        } else {
            host = hostAndPort;
            port = 80;
        }

        if (LittleProxyConfig.isUseDnsSec()) {
            return VerifiedAddressFactory.newInetSocketAddress(host, port,
                    LittleProxyConfig.isUseDnsSec());

        } else {
            InetAddress ia = InetAddress.getByName(host);
            String address = ia.getHostAddress();
            return new InetSocketAddress(address, port);
        }
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

    /***************************************************************************
     * Activity Tracking/Statistics
     **************************************************************************/
    protected void recordBytesReceivedFromClient(
            ProxyToServerConnection serverConnection,
            ConnectionTracer tracer) {
        int bytes = tracer.getBytesOnWire();
        FlowContext flowContext = new FlowContext(this, serverConnection);
        for (ActivityTracker tracker : activityTrackers) {
            tracker.bytesReceivedFromClient(flowContext, bytes);
        }
    }

    protected void recordRequestReceivedFromClient(
            String serverHostAndPort, String chainedProxyHostAndPort,
            HttpRequest httpRequest) {
        FlowContext flowContext = new FlowContext(getClientAddress(),
                serverHostAndPort, chainedProxyHostAndPort);
        for (ActivityTracker tracker : activityTrackers) {
            tracker.requestReceivedFromClient(flowContext, httpRequest);
        }
    }

    protected void recordRequestSentToServer(
            ProxyToServerConnection serverConnection, HttpRequest httpRequest) {
        FlowContext flowContext = new FlowContext(this, serverConnection);
        for (ActivityTracker tracker : activityTrackers) {
            tracker.requestSent(flowContext, httpRequest);
        }
    }

    protected void recordBytesReceivedFromServer(
            ProxyToServerConnection serverConnection,
            ConnectionTracer tracer) {
        int bytes = tracer.getBytesOnWire();
        FlowContext flowContext = new FlowContext(this, serverConnection);
        for (ActivityTracker tracker : activityTrackers) {
            tracker.bytesReceivedFromServer(flowContext, bytes);
        }
    }

    protected void recordResponseReceivedFromServer(
            ProxyToServerConnection serverConnection, HttpResponse httpResponse) {
        FlowContext flowContext = new FlowContext(this, serverConnection);
        for (ActivityTracker tracker : activityTrackers) {
            tracker.responseReceived(flowContext, httpResponse);
        }
    }

    public InetSocketAddress getClientAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

}
