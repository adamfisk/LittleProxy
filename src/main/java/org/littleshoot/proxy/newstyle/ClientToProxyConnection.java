package org.littleshoot.proxy.newstyle;

import static org.littleshoot.proxy.newstyle.ConnectionState.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.littleshoot.dnssec4j.VerifiedAddressFactory;
import org.littleshoot.proxy.ChainProxyManager;
import org.littleshoot.proxy.LittleProxyConfig;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.ProxyUtils;

/**
 * Represents a connection from a client to our proxy.
 */
public class ClientToProxyConnection extends ProxyConnection {

    private final ChainProxyManager chainProxyManager;
    private final ProxyAuthenticator authenticator;
    private final Map<String, ProxyToServerConnection> serverConnectionsByHostAndPort = new ConcurrentHashMap<String, ProxyToServerConnection>();
    private ProxyToServerConnection currentServerConnection;

    public ClientToProxyConnection(EventLoopGroup proxyToServerWorkerPool,
            ChannelGroup channelGroup, ChainProxyManager chainProxyManager,
            ProxyAuthenticator authenticator) {
        super(AWAITING_INITIAL, proxyToServerWorkerPool, channelGroup);
        this.chainProxyManager = chainProxyManager;
        this.authenticator = authenticator;
        LOG.debug("Created ClientToProxyConnection for {}", channel);
    }

    /***************************************************************************
     * Lifecycle Methods
     **************************************************************************/

    @Override
    protected ConnectionState readInitial(HttpObject httpObject) {
        HttpRequest request = (HttpRequest) httpObject;

        LOG.debug("Got request: {} on channel: " + channel, request);
        boolean authenticationRequired = this.authenticationRequired(request);
        if (authenticationRequired) {
            LOG.debug("Not authenticated!!");
            return AWAITING_PROXY_AUTHENTICATION;
        } else {
            LOG.debug("Identifying server");

            String hostAndPort = identifyHostAndPort(request);
            if (hostAndPort == null || StringUtils.isBlank(hostAndPort)) {
                LOG.warn("No host and port found in {}", request.getUri());
                writeBadGateway(request);
                return DISCONNECT_REQUESTED;
            }

            LOG.debug("Finding ProxyToServerConnection");
            currentServerConnection = this.serverConnectionsByHostAndPort
                    .get(hostAndPort);
            if (currentServerConnection == null) {
                try {
                    currentServerConnection = connect(request, hostAndPort);
                } catch (UnknownHostException uhe) {
                    LOG.info("Bad Host {}", request.getUri());
                    writeBadGateway(request);
                    resumeReading();
                    return DISCONNECT_REQUESTED;
                }
            }

            if (!LittleProxyConfig.isTransparent()) {
                boolean isChained = chainProxyManager != null
                        && chainProxyManager.getChainProxy(request) != null;
                LOG.debug("Modifying request for proxy chaining");
                request = ProxyUtils.copyHttpRequest(request, isChained);
            }

            // TODO: filter request

            LOG.debug("Writing request to ProxyToServerConnection");
            currentServerConnection.write(request);

            // if (connectionToWeb instanceof TunnelingProxyToWebConnection) {
            // return State.TUNNELING;
            // }

            return ProxyUtils.isChunked(request) ? AWAITING_CHUNK
                    : AWAITING_INITIAL;
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

    /**
     * Respond to the browser.
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
    public void respond(ProxyToServerConnection serverConnection,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject) {
        if (httpObject instanceof HttpResponse) {
            final HttpResponse httpResponse = (HttpResponse) httpObject;

            fixHttpVersionHeaderIfNecessary(httpResponse);
            modifyResponseHeadersToReflectProxying(httpResponse);

            // TODO: filter response
        }

        Future<Void> writeFuture = write(httpObject);
        if (ProxyUtils.isLastChunk(httpObject)) {
            writeFuture = writeEmptyBuffer();
        }

        synchronized (serverConnection) {
            if (isSaturated()) {
                LOG.debug("Browser connection is saturated, disabling reads from server");
                serverConnection.stopReading();
            }
        }

        closeConnectionsAfterWriteIfNecessary(serverConnection,
                currentHttpRequest, currentHttpResponse, httpObject,
                writeFuture);
    }

    /**
     * Write an empty buffer at the end of a chunked transfer. We need to do
     * this to handle the way Netty creates HttpChunks from responses that
     * aren't in fact chunked from the remote server using Transfer-Encoding:
     * chunked. Netty turns these into pseudo-chunked responses in cases where
     * the response would otherwise fill up too much memory or where the length
     * of the response body is unknown. This is handy because it means we can
     * start streaming response bodies back to the browser without reading the
     * entire response. The problem is that in these pseudo-cases the last chunk
     * is encoded to null, and this thwarts normal ChannelFutures from
     * propagating operationComplete events on writes to appropriate channel
     * listeners. We work around this by writing an empty buffer in those cases
     * and using the empty buffer's future instead to handle any operations we
     * need to when responses are fully written back to clients.
     */
    private Future<Void> writeEmptyBuffer() {
        return write(Unpooled.EMPTY_BUFFER);
    }

    /**
     * Chunked encoding is an HTTP 1.1 feature, but sometimes we get a chunked
     * response that reports its HTTP version as 1.0. In this case, we change it
     * to 1.1.
     * 
     * @param httpResponse
     */
    private void fixHttpVersionHeaderIfNecessary(HttpResponse httpResponse) {
        final String te = httpResponse.headers().get(
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
     * response headers to reflect that it was proxied.
     * 
     * @param httpResponse
     * @return
     */
    private void modifyResponseHeadersToReflectProxying(
            HttpResponse httpResponse) {
        if (!LittleProxyConfig.isTransparent()) {
            ProxyUtils.stripHopByHopHeaders(httpResponse);
            ProxyUtils.addVia(httpResponse);
        }
    }

    /**
     * This method takes care of closing browser to proxy and/or proxy to server
     * connections after finishing a write.
     * 
     * @param writeFuture
     */
    private void closeConnectionsAfterWriteIfNecessary(
            final ProxyToServerConnection serverConnection,
            HttpRequest currentHttpRequest, HttpResponse currentHttpResponse,
            HttpObject httpObject, Future<Void> writeFuture) {
        final boolean closeServerConnection = shouldCloseServerConnection(
                currentHttpRequest, currentHttpResponse, httpObject);
        final boolean closeClientConnection = shouldCloseClientConnection(
                currentHttpRequest, currentHttpResponse, httpObject);

        if (closeServerConnection) {
            LOG.debug("Closing remote connection after writing to browser");

            // We close after the future has completed to make sure that
            // all the response data is written to the browser --
            // closing immediately could trigger a close to the browser
            // as well before all the data has been written. Note that
            // in many cases a call to close the remote connection will
            // ultimately result in the connection to the browser closing,
            // particularly when there are no more remote connections
            // associated with that browser connection.
            writeFuture
                    .addListener(new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(
                                Future<? super Void> future) throws Exception {
                            LOG.debug("Closing ProxyToServerConnection now that last write to client has finished");
                            serverConnection.disconnect();
                        }
                    });
        }

        if (closeClientConnection) {
            LOG.debug("Closing connection to browser after writes");
            writeFuture
                    .addListener(new GenericFutureListener<Future<? super Void>>() {
                        @Override
                        public void operationComplete(
                                Future<? super Void> future) throws Exception {
                            LOG.debug("Closing ClientToProxyConnection now that last write to client has finished");
                            disconnect();
                        }
                    });
        }
    }

    protected void connectionToServerStarted(ProxyToServerConnection connection) {
        this.stopReading();
    }

    protected void connectionToServerSucceeded(
            ProxyToServerConnection connection) {
        this.resumeReading();
    }

    @Override
    protected void becameWriteable() {
        // When the ClientToProxyConnection becomes writeable, resume reading
        // on all associated ProxyToServerConnections
        for (ProxyToServerConnection serverConnection : serverConnectionsByHostAndPort
                .values()) {
            synchronized (serverConnection) {
                serverConnection.resumeReading();
            }
        }
    }

    protected void connectionToServerFailed(HttpRequest initialRequest) {
        // TODO: handle this
        // final String nextHostAndPort;
        // if (chainProxyManager == null) {
        // nextHostAndPort = initialRequest.getHostAndPort();
        // } else {
        // chainProxyManager.onCommunicationError(initialRequest.getHostAndPort());
        // nextHostAndPort =
        // chainProxyManager.getChainProxy(initialRequest.getRequest());
        // }
        //
        // if (initialRequest..equals(nextHostAndPort)) {
        // // We call the relay channel closed event handler
        // // with one associated unanswered request.
        // onRelayChannelClose(browserToProxyChannel, copiedHostAndPort, 1,
        // true);
        // } else {
        // // TODO I am not sure about this
        // removeProxyToWebConnection(copiedHostAndPort);
        // // try again with different hostAndPort
        // processRequest(ctx, httpObject);
        // }

    }

    /***************************************************************************
     * Private Implementation
     **************************************************************************/

    private ProxyToServerConnection connect(final HttpRequest request,
            final String hostAndPort) throws UnknownHostException {
        LOG.debug("Establishing new ProxyToServerConnection");
        InetSocketAddress address = addressFor(hostAndPort);
        ProxyToServerConnection connection = new ProxyToServerConnection(
                proxyToServerWorkerPool, channelGroup, this, address);
        serverConnectionsByHostAndPort.put(hostAndPort, connection);
        return connection;
    }

    private boolean authenticationRequired(HttpRequest request) {
        if (!request.headers().contains(HttpHeaders.Names.PROXY_AUTHORIZATION)) {
            if (this.authenticator != null) {
                writeAuthenticationRequired();
                return true;
            }
            return false;
        }

        final List<String> values = request.headers().getAll(
                HttpHeaders.Names.PROXY_AUTHORIZATION);
        final String fullValue = values.iterator().next();
        final String value = StringUtils.substringAfter(fullValue, "Basic ")
                .trim();
        final byte[] decodedValue = Base64.decodeBase64(value);
        try {
            final String decodedString = new String(decodedValue, "UTF-8");
            final String userName = StringUtils.substringBefore(decodedString,
                    ":");
            final String password = StringUtils.substringAfter(decodedString,
                    ":");
            if (!authenticator.authenticate(userName, password)) {
                writeAuthenticationRequired();
                return true;
            }
        } catch (final UnsupportedEncodingException e) {
            LOG.error("Could not decode?", e);
        }

        LOG.info("Got proxy authorization!");
        // We need to remove the header before sending the request on.
        final String authentication = request.headers().get(
                HttpHeaders.Names.PROXY_AUTHORIZATION);
        LOG.info(authentication);
        request.headers().remove(HttpHeaders.Names.PROXY_AUTHORIZATION);
        return false;
    }

    private void writeAuthenticationRequired() {
        final String body = "<!DOCTYPE HTML PUBLIC \"-//IETF//DTD HTML 2.0//EN\">\n"
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
        this.write(response);
    }

    private void writeBadGateway(final HttpRequest request) {
        final String body = "Bad Gateway: " + request.getUri();
        DefaultFullHttpResponse response = responseFor(HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY, body);
        response.headers().set(HttpHeaders.Names.CONNECTION, "close");
        this.write(response);
    }

    private DefaultFullHttpResponse responseFor(HttpVersion httpVersion,
            HttpResponseStatus status, String body) {
        byte[] bytes = body.getBytes(Charset.forName("UTF-8"));
        ByteBuf content = Unpooled.copiedBuffer(bytes);
        DefaultFullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status, content);
        response.headers().set(HttpHeaders.Names.CONTENT_LENGTH, bytes.length);
        response.headers().set("Content-Type", "text/html; charset=UTF-8");
        return response;
    }

    /**
     * Identify the host and port for a request.
     * 
     * @param request
     * @return
     */
    private String identifyHostAndPort(HttpRequest request) {
        if (this.chainProxyManager != null) {
            return this.chainProxyManager.getChainProxy(request);
        }

        String hostAndPort = ProxyUtils.parseHostAndPort(request);
        if (StringUtils.isBlank(hostAndPort)) {
            final List<String> hosts = request.headers().getAll(
                    HttpHeaders.Names.HOST);
            if (hosts != null && !hosts.isEmpty()) {
                hostAndPort = hosts.get(0);
            }
        }

        return hostAndPort;
    }

    private InetSocketAddress addressFor(String hostAndPort)
            throws UnknownHostException {
        final String host;
        final int port;
        if (hostAndPort.contains(":")) {
            host = StringUtils.substringBefore(hostAndPort, ":");
            final String portString = StringUtils.substringAfter(hostAndPort,
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
            final InetAddress ia = InetAddress.getByName(host);
            final String address = ia.getHostAddress();
            return new InetSocketAddress(address, port);
        }
    }

    private boolean shouldCloseClientConnection(final HttpRequest req,
            final HttpResponse res, final HttpObject httpObject) {
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
                    LOG.debug("Last chunk...using normal closing rules");
                }
            }
        }

        if (!HttpHeaders.isKeepAlive(req)) {
            LOG.debug("Closing since request is not keep alive:");
            // Here we simply want to close the connection because the
            // browser itself has requested it be closed in the request.
            return true;
        }
        LOG.debug("Not closing browser/client to proxy connection "
                + "for request: {}", req);
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
    private boolean shouldCloseServerConnection(final HttpRequest req,
            final HttpResponse res, final HttpObject msg) {
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
            // browser itself has requested it be closed in the request.
            return true;
        }
        if (!HttpHeaders.isKeepAlive(res)) {
            LOG.debug("Closing since response is not keep alive:{}", res);
            // In this case, we want to honor the Connection: close header
            // from the remote server and close that connection. We don't
            // necessarily want to close the connection to the browser, however
            // as it's possible it has other connections open.
            return true;
        }
        LOG.debug("Not closing -- response probably keep alive for:\n{}", res);
        return false;
    }

    private boolean closeEndsResponseBody(final HttpResponse httpResponse) {
        final String cl = httpResponse.headers().get(
                HttpHeaders.Names.CONTENT_LENGTH);
        if (StringUtils.isNotBlank(cl)) {
            return false;
        }
        final String te = httpResponse.headers().get(
                HttpHeaders.Names.TRANSFER_ENCODING);
        if (StringUtils.isNotBlank(te)
                && te.equalsIgnoreCase(HttpHeaders.Values.CHUNKED)) {
            return false;
        }
        return true;
    }
}
