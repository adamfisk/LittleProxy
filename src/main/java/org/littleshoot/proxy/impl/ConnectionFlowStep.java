package org.littleshoot.proxy.impl;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.util.concurrent.Future;

/**
 * Represents a phase in a {@link ConnectionFlow}.
 */
abstract class ConnectionFlowStep {
    private final ProxyConnectionLogger LOG;
    private final ProxyConnection connection;
    private final ConnectionState state;
    private final boolean suppressInitialRequest;

    /**
     * Construct a new step in a connection flow. This step does not suppress
     * the initial {@link HttpRequest}.
     * 
     * @param connection
     *            the connection that we're working on
     * @param state
     *            the state that the connection will show while we're processing
     *            this step
     */
    ConnectionFlowStep(ProxyConnection connection,
            ConnectionState state) {
        this(connection, state, false);
    }

    /**
     * Construct a new step in a connection flow.
     * 
     * @param connection
     *            the connection that we're working on
     * @param state
     *            the state that the connection will show while we're processing
     *            this step
     * @param suppressInitialRequest
     *            set to true if the inclusion of this step should prevent the
     *            initial {@link HttpRequest} that spawned our connection from
     *            being set after we connect successfully
     */
    ConnectionFlowStep(ProxyConnection connection,
            ConnectionState state,
            boolean suppressInitialRequest) {
        super();
        this.connection = connection;
        this.state = state;
        this.suppressInitialRequest = suppressInitialRequest;
        this.LOG = connection.getLOG();
    }

    ProxyConnection getConnection() {
        return connection;
    }

    ConnectionState getState() {
        return state;
    }

    boolean isSuppressInitialRequest() {
        return suppressInitialRequest;
    }

    /**
     * Implement this method to actually do the work involved in this step of
     * the flow.
     * 
     * @return
     */
    protected abstract Future execute();

    /**
     * When the flow determines that this step was successful, it calls into
     * this method. The default implementation simply continues with the flow.
     * Other implementations may choose to not continue and instead wait for a
     * message or something like that.
     * 
     * @param flow
     */
    void onSuccess(ConnectionFlow flow) {
        flow.advance();
    }

    /**
     * <p>
     * Any messages that are read from the underlying connection while we're at
     * this step of the connection flow are passed to this method.
     * </p>
     * 
     * <p>
     * The default implementation ignores the message and logs this, since we
     * weren't really expecting a message here.
     * </p>
     * 
     * <p>
     * Some {@link ConnectionFlowStep}s do need to read the messages, so they
     * override this method as appropriate.
     * </p>
     * 
     * @param flow
     *            our {@link ConnectionFlow}
     * @param msg
     *            the message read from the underlying connection
     */
    void read(ConnectionFlow flow, Object msg) {
        LOG.debug("Received message while in the middle of connecting: {}", msg);
    }

    @Override
    public String toString() {
        return state.toString();
    }

}
