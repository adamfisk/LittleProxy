package org.littleshoot.proxy.impl;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Coordinates the various steps involved in establishing a connection, such as
 * establishing a socket connection, SSL handshaking, HTTP CONNECT request
 * processing, and so on.
 */
class ConnectionFlow {
    private Queue<ConnectionFlowStep> steps = new ConcurrentLinkedQueue<ConnectionFlowStep>();

    private final ClientToProxyConnection clientConnection;
    private final ProxyToServerConnection serverConnection;
    private final Object connectLock;
    private volatile ConnectionFlowStep currentStep;
    private volatile boolean suppressInitialRequest = false;

    ConnectionFlow(
            ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection,
            Object connectLock) {
        super();
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
        this.connectLock = connectLock;
    }

    ConnectionFlow startWith(ConnectionFlowStep step) {
        return then(step);
    }

    ConnectionFlow then(ConnectionFlowStep step) {
        steps.add(step);
        return this;
    }

    void read(Object msg) {
        if (this.currentStep != null) {
            this.currentStep.read(this, msg);
        }
    }

    void start() {
        clientConnection.serverConnectionFlowStarted(serverConnection);
        go();
    }

    void go() {
        synchronized (connectLock) {
            currentStep = steps.poll();
            if (currentStep == null) {
                succeed();
            } else {
                final ProxyConnection connection = currentStep.getConnection();
                final ProxyConnectionLogger LOG = connection.getLOG();
                LOG.debug("Executing connection flow step: {}", currentStep);
                connection.become(currentStep.getState());
                suppressInitialRequest = suppressInitialRequest
                        || currentStep.isSuppressInitialRequest();
                currentStep.execute().addListener(
                        new GenericFutureListener<Future>() {
                            public void operationComplete(Future future)
                                    throws Exception {
                                if (future.isSuccess()) {
                                    LOG.debug("ConnectionFlowStep succeeded");
                                    currentStep.onSuccess(ConnectionFlow.this);
                                } else {
                                    LOG.debug("ConnectionFlowStep failed: {}",
                                            future.cause());
                                    fail();
                                }
                            };
                        });
            }
        }
    }

    void succeed() {
        serverConnection.getLOG().debug(
                "Connection flow completed successfully: {}", currentStep);
        serverConnection.connectionSucceeded(!suppressInitialRequest);
    }

    void fail() {
        ConnectionState lastStateBeforeFailure = serverConnection
                .getCurrentState();
        serverConnection.disconnect();
        clientConnection.serverConnectionFailed(
                serverConnection,
                lastStateBeforeFailure);
    }
}
