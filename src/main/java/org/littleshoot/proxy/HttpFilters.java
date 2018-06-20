package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.littleshoot.proxy.impl.ProxyUtils;

import java.net.InetSocketAddress;

/**
 * <p>
 * Interface for objects that filter {@link HttpObject}s, including both
 * requests and responses, and informs of different steps in request/response.
 * </p>
 * 
 * <p>
 * Multiple methods are defined, corresponding to different steps in the request
 * processing lifecycle. Some of these methods is given the current object
 * (request, response or chunk) and is allowed to modify it in place. Others
 * provide a notification of when specific operations happen (i.e. connection in
 * queue, DNS resolution, SSL handshaking and so forth).
 * </p>
 * 
 * <p>
 * Because HTTP transfers can be chunked, for any given request or response, the
 * filter methods that can modify request/response in place may be called
 * multiple times, once for the initial {@link HttpRequest} or
 * {@link HttpResponse}, and once for each subsequent {@link HttpContent}. The
 * last chunk will always be a {@link LastHttpContent} and can be checked for
 * being last using {@link ProxyUtils#isLastChunk(HttpObject)}.
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
 * <p>
 * To monitor (and time measure?) the different steps the request/response goes
 * through, many informative methods are provided. Those steps are reported in
 * the following order:
 * <ol>
 * <li>clientToProxyRequest</li>
 * <li>proxyToServerConnectionQueued</li>
 * <li>proxyToServerResolutionStarted</li>
 * <li>proxyToServerResolutionSucceeded</li>
 * <li>proxyToServerRequest (can be multiple if chunked)</li>
 * <li>proxyToServerConnectionStarted</li>
 * <li>proxyToServerConnectionFailed (if connection couldn't be established)</li>
 * <li>proxyToServerConnectionSSLHandshakeStarted (only if HTTPS required)</li>
 * <li>proxyToServerConnectionSucceeded</li>
 * <li>proxyToServerRequestSending</li>
 * <li>proxyToServerRequestSent</li>
 * <li>serverToProxyResponseReceiving</li>
 * <li>serverToProxyResponse (can be multiple if chuncked)</li>
 * <li>serverToProxyResponseReceived</li>
 * <li>proxyToClientResponse</li>
 * </ol>
 */
public interface HttpFilters {
    /**
     * Filters requests on their way from the client to the proxy. To interrupt processing of this request and return a
     * response to the client immediately, return an HttpResponse here. Otherwise, return null to continue processing as
     * usual.
     * <p>
     * <b>Important:</b> When returning a response, you must include a mechanism to allow the client to determine the length
     * of the message (see RFC 7230, section 3.3.3: https://tools.ietf.org/html/rfc7230#section-3.3.3 ). For messages that
     * may contain a body, you may do this by setting the Transfer-Encoding to chunked, setting an appropriate
     * Content-Length, or by adding a "Connection: close" header to the response (which will instruct LittleProxy to close
     * the connection). If the short-circuit response contains body content, it is recommended that you return a
     * FullHttpResponse.
     * 
     * @param httpObject Client to Proxy HttpRequest (and HttpContent, if chunked)
     * @return a short-circuit response, or null to continue processing as usual
     */
    HttpResponse clientToProxyRequest(HttpObject httpObject);

    /**
     * Filters requests on their way from the proxy to the server. To interrupt processing of this request and return a
     * response to the client immediately, return an HttpResponse here. Otherwise, return null to continue processing as
     * usual.
     * <p>
     * <b>Important:</b> When returning a response, you must include a mechanism to allow the client to determine the length
     * of the message (see RFC 7230, section 3.3.3: https://tools.ietf.org/html/rfc7230#section-3.3.3 ). For messages that
     * may contain a body, you may do this by setting the Transfer-Encoding to chunked, setting an appropriate
     * Content-Length, or by adding a "Connection: close" header to the response. (which will instruct LittleProxy to close
     * the connection). If the short-circuit response contains body content, it is recommended that you return a
     * FullHttpResponse.
     * 
     * @param httpObject Proxy to Server HttpRequest (and HttpContent, if chunked)
     * @return a short-circuit response, or null to continue processing as usual
     */
    HttpResponse proxyToServerRequest(HttpObject httpObject);

    /**
     * Informs filter that proxy to server request is being sent.
     */
    void proxyToServerRequestSending();

    /**
     * Informs filter that the HTTP request, including any content, has been sent.
     */
    void proxyToServerRequestSent();

    /**
     * Filters responses on their way from the server to the proxy.
     * 
     * @param httpObject
     *            Server to Proxy HttpResponse (and HttpContent, if chunked)
     * @return the modified (or unmodified) HttpObject. Returning null will
     *         force a disconnect.
     */
    HttpObject serverToProxyResponse(HttpObject httpObject);

    /**
     * Informs filter that a timeout occurred before the server response was received by the client. The timeout may have
     * occurred while the client was sending the request, waiting for a response, or after the client started receiving
     * a response (i.e. if the response from the server "stalls").
     *
     * See {@link HttpProxyServerBootstrap#withIdleConnectionTimeout(int)} for information on setting the timeout.
     */
    void serverToProxyResponseTimedOut();

    /**
     * Informs filter that server to proxy response is being received.
     */
    void serverToProxyResponseReceiving();

    /**
     * Informs filter that server to proxy response has been received.
     */
    void serverToProxyResponseReceived();

    /**
     * Filters responses on their way from the proxy to the client.
     * 
     * @param httpObject
     *            Proxy to Client HttpResponse (and HttpContent, if chunked)
     * @return the modified (or unmodified) HttpObject. Returning null will
     *         force a disconnect.
     */
    HttpObject proxyToClientResponse(HttpObject httpObject);

    /**
     * Informs filter that proxy to server connection is in queue.
     */
    void proxyToServerConnectionQueued();

    /**
     * Filter DNS resolution from proxy to server.
     * 
     * @param resolvingServerHostAndPort
     *            Server "HOST:PORT"
     * @return alternative address resolution. Returning null will let normal
     *         DNS resolution continue.
     */
    InetSocketAddress proxyToServerResolutionStarted(
            String resolvingServerHostAndPort);

    /**
     * Informs filter that proxy to server DNS resolution failed for the specified host and port.
     *
     * @param hostAndPort hostname and port the proxy failed to resolve
     */
    void proxyToServerResolutionFailed(String hostAndPort);

    /**
     * Informs filter that proxy to server DNS resolution has happened.
     * 
     * @param serverHostAndPort
     *            Server "HOST:PORT"
     * @param resolvedRemoteAddress
     *            Address it was proxyToServerResolutionSucceeded to
     */
    void proxyToServerResolutionSucceeded(String serverHostAndPort,
            InetSocketAddress resolvedRemoteAddress);

    /**
     * Informs filter that proxy to server connection is initiating.
     */
    void proxyToServerConnectionStarted();

    /**
     * Informs filter that proxy to server ssl handshake is initiating.
     */
    void proxyToServerConnectionSSLHandshakeStarted();

    /**
     * Informs filter that proxy to server connection has failed.
     */
    void proxyToServerConnectionFailed();

    /**
     * Informs filter that proxy to server connection has succeeded.
     *
     * @param serverCtx the {@link io.netty.channel.ChannelHandlerContext} used to connect to the server
     */
    void proxyToServerConnectionSucceeded(ChannelHandlerContext serverCtx);

    /**
     * Informs filter that server disconnected while request was still in flight
     */
    void proxyToServerDisconnected();
}
