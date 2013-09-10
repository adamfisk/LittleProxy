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

    private boolean partOfConnectionFlow;

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
     * @return
     */
    public boolean isPartOfConnectionFlow() {
        return partOfConnectionFlow;
    }

}
