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
     * Record that the proxy received bytes from the client.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param numberOfBytes
     */
    void bytesReceivedFromClient(FlowContext flowContext, int numberOfBytes);

    /**
     * Record that proxy received an {@link HttpRequest} from the client.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpRequest
     */
    void requestReceivedFromClient(FlowContext flowContext,
            HttpRequest httpRequest);

    /**
     * Record that proxy attempted to send a request to the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpRequest
     */
    void requestSent(FlowContext flowContext, HttpRequest httpRequest);

    /**
     * Record that the proxy received bytes from the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param numberOfBytes
     */
    void bytesReceivedFromServer(FlowContext flowContext, int numberOfBytes);

    /**
     * Record that the proxy received an {@link HttpResponse} from the server.
     * 
     * @param flowContext
     *            provides contextual information about the flow
     * @param httpResponse
     */
    void responseReceived(FlowContext flowContext, HttpResponse httpResponse);

}
