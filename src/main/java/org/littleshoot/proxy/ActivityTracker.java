package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

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
     * Record that bytes were received from the client.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param numberOfBytes
     */
    void bytesReceivedFromClient(FlowContext flowContext, int numberOfBytes);

    /**
     * Record that a request was received from the client to the proxy.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpRequest
     */
    void requestReceivedFromClient(FlowContext flowContext,
            HttpRequest httpRequest);

    /**
     * Record that a request was sent from client to server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpRequest
     */
    void requestSent(FlowContext flowContext, HttpRequest httpRequest);

    /**
     * Record that bytes were received from server the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param numberOfBytes
     */
    void bytesReceivedFromServer(FlowContext flowContext, int numberOfBytes);

    /**
     * Record that a response was received from the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpResponse
     */
    void responseReceived(FlowContext flowContext, HttpResponse httpResponse);

}
