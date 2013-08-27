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

    private boolean partOfConnectFlow;

    ConnectionState(boolean partOfConnectionFlow) {
        this.partOfConnectFlow = partOfConnectionFlow;
    }

    ConnectionState() {
        this(false);
    }

    public boolean isPartOfConnectFlow() {
        return partOfConnectFlow;
    }

}
