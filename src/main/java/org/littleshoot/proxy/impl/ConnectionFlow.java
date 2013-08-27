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

    /**
     * Construct a new {@link ConnectionFlow} for the given client and server
     * connections.
     * 
     * @param clientConnection
     * @param serverConnection
     * @param connectLock
     *            an object that's shared by {@link ConnectionFlow} and
     *            {@link ProxyToServerConnection} and that is used for
     *            synchronizing the reader and writer threads that are both
     *            involved during the establishing of a connection.
     */
    ConnectionFlow(
            ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection,
            Object connectLock) {
        super();
        this.clientConnection = clientConnection;
        this.serverConnection = serverConnection;
        this.connectLock = connectLock;
    }

    /**
     * Add a {@link ConnectionFlowStep} to this flow.
     * 
     * @param step
     * @return
     */
    ConnectionFlow then(ConnectionFlowStep step) {
        steps.add(step);
        return this;
    }

    /**
     * While we're in the process of connecting, any messages read by the
     * {@link ProxyToServerConnection} are passed to this method, which passes
     * it on to {@link ConnectionFlowStep#read(ConnectionFlow, Object)} for the
     * current {@link ConnectionFlowStep}.
     * 
     * @param msg
     */
    void read(Object msg) {
        if (this.currentStep != null) {
            this.currentStep.read(this, msg);
        }
    }

    /**
     * Starts the connection flow, notifying the {@link ClientToProxyConnection}
     * that we've started.
     */
    void start() {
        clientConnection.serverConnectionFlowStarted(serverConnection);
        advance();
    }

    /**
     * <p>
     * Advances the flow. {@link #advance()} will be called until we're either
     * out of steps, or a step has failed.
     * </p>
     */
    void advance() {
        synchronized (connectLock) {
            currentStep = steps.poll();
            if (currentStep == null) {
                succeed();
            } else {
                processCurrentStep();
            }
        }
    }

    /**
     * <p>
     * Process the current {@link ConnectionFlowStep}. With each step, we:
     * </p>
     * 
     * <ol>
     * <li>Change the state of the associated {@link ProxyConnection} to the
     * value of {@link ConnectionFlowStep#getState()}</li>
     * <li>Call {@link ConnectionFlowStep#execute()}</li>
     * <li>On completion of the {@link Future} returned by
     * {@link ConnectionFlowStep#execute()}, check the success.</li>
     * <li>If successful, we call back into
     * {@link ConnectionFlowStep#onSuccess(ConnectionFlow)}.</li>
     * <li>If unsuccessful, we call {@link #fail()}, stopping the connection
     * flow</li>
     * </ol>
     */
    private void processCurrentStep() {
        final ProxyConnection<?> connection = currentStep.getConnection();
        final ProxyConnectionLogger LOG = connection.getLOG();

        LOG.debug("Processing connection flow step: {}", currentStep);
        connection.become(currentStep.getState());
        suppressInitialRequest = suppressInitialRequest
                || currentStep.isSuppressInitialRequest();
        currentStep.execute().addListener(
                new GenericFutureListener<Future>() {
                    public void operationComplete(Future future)
                            throws Exception {
                        synchronized (connectLock) {
                            if (future.isSuccess()) {
                                LOG.debug("ConnectionFlowStep succeeded");
                                currentStep.onSuccess(ConnectionFlow.this);
                            } else {
                                LOG.debug("ConnectionFlowStep failed",
                                        future.cause());
                                fail();
                            }
                        }
                    };
                });
    }

    /**
     * Called when the flow is complete and successful. Notifies the
     * {@link ProxyToServerConnection} that we succeeded.
     */
    void succeed() {
        serverConnection.getLOG().debug(
                "Connection flow completed successfully: {}", currentStep);
        serverConnection.connectionSucceeded(!suppressInitialRequest);
    }

    /**
     * Called when the flow fails at some {@link ConnectionFlowStep}.
     * Disconnects the {@link ProxyToServerConnection} and informs the
     * {@link ClientToProxyConnection} that our connection failed.
     */
    void fail() {
        ConnectionState lastStateBeforeFailure = serverConnection
                .getCurrentState();
        serverConnection.disconnect();
        clientConnection.serverConnectionFailed(
                serverConnection,
                lastStateBeforeFailure);
    }
}
