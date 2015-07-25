package org.littleshoot.proxy.impl;

enum ConnectionState {
    /**
     * Connection attempting to connect.
     */
    CONNECTING(true),

    /**
     * In the middle of doing an SSL handshake.
     */
    HANDSHAKING(true),

    /**
     * In the process of negotiating an HTTP CONNECT from the client.
     */
    NEGOTIATING_CONNECT(true),

    /**
     * When forwarding a CONNECT to a chained proxy, we await the CONNECTION_OK
     * message from the proxy.
     */
    AWAITING_CONNECT_OK(true),

    /**
     * Connected but waiting for proxy authentication.
     */
    AWAITING_PROXY_AUTHENTICATION,

    /**
     * Connected and awaiting initial message (e.g. HttpRequest or
     * HttpResponse).
     */
    AWAITING_INITIAL,

    /**
     * Connected and awaiting HttpContent chunk.
     */
    AWAITING_CHUNK,

    /**
     * We've asked the client to disconnect, but it hasn't yet.
     */
    DISCONNECT_REQUESTED(),

    /**
     * Disconnected
     */
    DISCONNECTED();

    private final boolean partOfConnectionFlow;

    ConnectionState(boolean partOfConnectionFlow) {
        this.partOfConnectionFlow = partOfConnectionFlow;
    }

    ConnectionState() {
        this(false);
    }

    /**
     * Indicates whether this ConnectionState corresponds to a step in a
     * {@link ConnectionFlow}. This is useful to distinguish so that we know
     * whether or not we're in the process of establishing a connection.
     * 
     * @return true if part of connection flow, otherwise false
     */
    public boolean isPartOfConnectionFlow() {
        return partOfConnectionFlow;
    }

    /**
     * Indicates whether this ConnectionState is no longer waiting for messages and is either in the process of disconnecting
     * or is already disconnected.
     *
     * @return true if the connection state is {@link #DISCONNECT_REQUESTED} or {@link #DISCONNECTED}, otherwise false
     */
    public boolean isDisconnectingOrDisconnected() {
        return this == DISCONNECT_REQUESTED || this == DISCONNECTED;
    }
}
