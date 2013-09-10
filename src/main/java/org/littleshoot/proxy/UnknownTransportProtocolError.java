package org.littleshoot.proxy;

/**
 * This error indicates that the system was asked to use a TransportProtocol
 * that it didn't know how to handle.
 */
public class UnknownTransportProtocolError extends Error {
    private static final long serialVersionUID = 1L;

    public UnknownTransportProtocolError(TransportProtocol transportProtocol) {
        super(String.format("Unknown TransportProtocol: %1$s",
                transportProtocol));
    }
}
