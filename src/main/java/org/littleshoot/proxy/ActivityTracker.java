package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * <p>
 * Interface for receiving information about activity in the proxy.
 * </p>
 * 
 * <p>
 * Sub-classes may wish to extend {@link ActivityTrackerAdapter} for sensible
 * defaults.
 * </p>
 */
public interface ActivityTracker {

    /**
     * Record that a client connected.
     */
    void clientConnected(InetSocketAddress clientAddress);

    /**
     * Record that a client's SSL handshake completed.
     */
    void clientSSLHandshakeSucceeded(InetSocketAddress clientAddress,
            SSLSession sslSession);

    /**
     * Record that a client disconnected.
     */
    void clientDisconnected(InetSocketAddress clientAddress,
            SSLSession sslSession);

    /**
     * Record that the proxy received bytes from the client.
     * 
     * @param flowContext
     *            if full information is available, this will be a
     *            {@link FullFlowContext}.
     * @param numberOfBytes
     */
    void bytesReceivedFromClient(FlowContext flowContext,
            int numberOfBytes);

    /**
     * <p>
     * Record that proxy received an {@link HttpRequest} from the client.
     * </p>
     * 
     * <p>
     * Note - on chunked transfers, this is only called once (for the initial
     * HttpRequest object).
     * </p>
     * 
     * @param flowContext
     *            if full information is available, this will be a
     *            {@link FullFlowContext}.
     * @param httpRequest
     */
    void requestReceivedFromClient(FlowContext flowContext,
            HttpRequest httpRequest);

    /**
     * Record that the proxy attempted to send bytes to the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param numberOfBytes
     */
    void bytesSentToServer(FullFlowContext flowContext, int numberOfBytes);

    /**
     * <p>
     * Record that proxy attempted to send a request to the server.
     * </p>
     * 
     * <p>
     * Note - on chunked transfers, this is only called once (for the initial
     * HttpRequest object).
     * </p>
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpRequest
     */
    void requestSentToServer(FullFlowContext flowContext,
            HttpRequest httpRequest);

    /**
     * Record that the proxy received bytes from the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param numberOfBytes
     */
    void bytesReceivedFromServer(FullFlowContext flowContext, int numberOfBytes);

    /**
     * <p>
     * Record that the proxy received an {@link HttpResponse} from the server.
     * </p>
     * 
     * <p>
     * Note - on chunked transfers, this is only called once (for the initial
     * HttpRequest object).
     * </p>
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpResponse
     */
    void responseReceivedFromServer(FullFlowContext flowContext,
            HttpResponse httpResponse);

    /**
     * Record that the proxy sent bytes to the client.
     * 
     * @param flowContext
     *            if full information is available, this will be a
     *            {@link FullFlowContext}.
     * @param numberOfBytes
     */
    void bytesSentToClient(FlowContext flowContext, int numberOfBytes);

    /**
     * <p>
     * Record that the proxy sent a response to the client.
     * </p>
     * 
     * <p>
     * Note - on chunked transfers, this is only called once (for the initial
     * HttpRequest object).
     * </p>
     * 
     * @param flowContext
     *            if full information is available, this will be a
     *            {@link FullFlowContext}.
     * @param httpResponse
     */
    void responseSentToClient(FlowContext flowContext,
            HttpResponse httpResponse);

}
