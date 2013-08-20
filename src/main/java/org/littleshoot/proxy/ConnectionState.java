package org.littleshoot.proxy;

public enum ConnectionState {
    CONNECTING, // Connection attempting to connect
    HANDSHAKING, // In the middle of doing an SSL handshake
    AWAITING_PROXY_AUTHENTICATION, // Connected but waiting for proxy
    // authentication,
    NEGOTIATING_CONNECT, // In the process of negotiating an HTTP CONNECT from
                         // the client
    AWAITING_CONNECT_OK, // When forwarding a CONNECT to a chained proxy, we
                            // await the CONNECTION_OK message from the proxy
    AWAITING_INITIAL, // Connected and awaiting initial message (e.g.
                      // HttpRequest or HttpResponse)
    AWAITING_CHUNK, // Connected and awaiting HttpContent chunk
    TUNNELING, // Connected and tunneling raw ByteBufs
    DISCONNECT_REQUESTED, // We've asked the client to disconnect, but it hasn't
                          // yet
    DISCONNECTED // Disconnected
}
