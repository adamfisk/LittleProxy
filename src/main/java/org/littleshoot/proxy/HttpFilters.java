package org.littleshoot.proxy;

import io.netty.handler.codec.http.*;
import org.littleshoot.proxy.impl.ProxyUtils;

import java.net.InetSocketAddress;

/**
 * <p>
 * Interface for objects that filter {@link HttpObject}s, including both
 * requests and responses.
 * </p>
 * 
 * <p>
 * Multiple methods are defined, corresponding to different steps in the request
 * processing lifecycle. Each of these methods is given the current object
 * (request, response or chunk) and is allowed to modify it in place.
 * </p>
 * 
 * <p>
 * Because HTTP transfers can be chunked, for any given request or response, the
 * filter methods may be called multiple times, once for the initial
 * {@link HttpRequest} or {@link HttpResponse}, and once for each subsequent
 * {@link HttpContent}. The last chunk will always be a {@link LastHttpContent}
 * and can be checked for being last using
 * {@link ProxyUtils#isLastChunk(HttpObject)}.
 * </p>
 * 
 * <p>
 * {@link HttpFiltersSource#getMaximumRequestBufferSizeInBytes()} and
 * {@link HttpFiltersSource#getMaximumResponseBufferSizeInBytes()} can be used
 * to instruct the proxy to buffer the {@link HttpObject}s sent to all of its
 * request/response filters, in which case it will buffer up to the specified
 * limit and then send either complete {@link HttpRequest}s or
 * {@link HttpResponse}s to the filter methods. When buffering, if the proxy
 * receives more data than fits in the specified maximum bytes to buffer, the
 * proxy will stop processing the request and respond with a 502 Bad Gateway
 * error.
 * </p>
 * 
 * <p>
 * A new instance of {@link HttpFilters} is created for each request, so these
 * objects can be stateful.
 * </p>
 * 
 */
public interface HttpFilters {
    /**
     * Filters requests on their way from the client to the proxy.
     * 
     * @param httpObject Client to Proxy HttpRequest (and HttpContent, if chunked)
     * @return if you want to interrupted processing and return a response to
     *         the client, return it here, otherwise return null to continue
     *         processing as usual
     */
    HttpResponse clientToProxyRequestPreProcessing(HttpObject httpObject);

    /**
     * Filters requests on their way from the proxy to the server.
     * 
     * @param httpObject Proxy to Server HttpRequest (and HttpContent, if chunked)
     * @return if you want to interrupted processing and return a response to
     *         the client, return it here, otherwise return null to continue
     *         processing as usual
     */
    HttpResponse proxyToServerRequestPreProcessing(HttpObject httpObject);

    /**
     * TODO
     */
    void proxyToServerRequestSending();

    /**
     * TODO
     */
    void proxyToServerRequestSent();

    /**
     * Filters responses on their way from the server to the proxy.
     * 
     * @param httpObject Server to Proxy HttpResponse (and HttpContent, if chunked)
     * @return the modified (or unmodified) HttpObject. Returning null will
     *         force a disconnect.
     */
    HttpObject serverToProxyResponsePreProcessing(HttpObject httpObject);

    /**
     * TODO
     */
    void serverToProxyResponseReceiving();

    /**
     * TODO
     */
    void serverToProxyResponseReceived();

    /**
     * Filters responses on their way from the proxy to the client.
     * 
     * @param httpObject Proxy to Client HttpResponse (and HttpContent, if chunked)
     * @return the modified (or unmodified) HttpObject. Returning null will
     *         force a disconnect.
     */
    HttpObject proxyToClientResponsePreProcessing(HttpObject httpObject);

    /**
     * Inform filter that proxy to server connection is in queue.
     */
    void proxyToServerAwaitingConnection();

    /**
     * Filter DNS resolution from proxy to server.
     *
     * @param resolvingServerHostAndPort Server "HOST:PORT"
     * @return alternative address resolution. Returning null will let
     *         normal DNS resolution continue.
     */
    InetSocketAddress proxyToServerResolving(String resolvingServerHostAndPort);

    /**
     * Inform filter that proxy to server DNS resolution has happened.
     *
     * @param serverHostAndPort Server "HOST:PORT"
     * @param resolvedRemoteAddress Address it was proxyToServerResolved to
     */
    void proxyToServerResolved(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress);

    /**
     * Inform filter that proxy to server connection is initiating.
     */
    void proxyToServerConnecting();

    /**
     * Inform filter that proxy to server ssl handshake is initiating.
     */
    void proxyToServerSSLHandshaking();

    /**
     * Inform filter that proxy to server connection has failed.
     */
    void proxyToServerConnectionFailed();

    /**
     * Inform filter that proxy to server connection has succeeded.
     */
    void proxyToServerConnectionSuccess();

}
