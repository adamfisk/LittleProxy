package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Adapter of {@link ActivityTracker} interface that provides default no-op
 * implementations of all methods.
 */
public class ActivityTrackerAdapter implements ActivityTracker {

    @Override
    public void bytesReceivedFromClient(FlowContext flowContext,
            int numberOfBytes) {
    }

    @Override
    public void requestReceivedFromClient(FlowContext flowContext,
            HttpRequest httpRequest) {
    }

    @Override
    public void requestSent(FlowContext flowContext,
            HttpRequest httpRequest) {
    }

    @Override
    public void bytesReceivedFromServer(FlowContext flowContext,
            int numberOfBytes) {
    }

    @Override
    public void responseReceived(FlowContext flowContext,
            HttpResponse httpResponse) {
    }

}
