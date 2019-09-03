package org.littleshoot.proxy.impl;

import io.netty.util.concurrent.Future;

/**
 * Represents a phase in a {@link ConnectionFlow}.
 */
abstract class ConnectionFlowStep {
    private final ProxyConnectionLogger LOG;
    private final ProxyConnection connection;
    private final ConnectionState state;

    /**
     * Construct a new step in a connection flow.
     * 
     * @param connection
     *            the connection that we're working on
     * @param state
     *            the state that the connection will show while we're processing
     *            this step
     */
    ConnectionFlowStep(ProxyConnection connection,
            ConnectionState state) {
        super();
        this.connection = connection;
        this.state = state;
        this.LOG = connection.getLOG();
    }

    ProxyConnection getConnection() {
        return connection;
    }

    ConnectionState getState() {
        return state;
    }

    /**
     * Indicates whether or not to suppress the initial request. Defaults to
     * false, can be overridden.
     */
    boolean shouldSuppressInitialRequest() {
        return false;
    }

    /**
     * <p>
     * Indicates whether or not this step should be executed on the channel's
     * event loop. Defaults to true, can be overridden.
     * </p>
     * 
     * <p>
     * If this step modifies the pipeline, for example by adding/removing
     * handlers, it's best to make it execute on the event loop.
     * </p>
     */
    boolean shouldExecuteOnEventLoop() {
        return true;
    }

    /**
     * Implement this method to actually do the work involved in this step of
     * the flow.
     */
    protected abstract Future execute();

    /**
     * When the flow determines that this step was successful, it calls into
     * this method. The default implementation simply continues with the flow.
     * Other implementations may choose to not continue and instead wait for a
     * message or something like that.
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
