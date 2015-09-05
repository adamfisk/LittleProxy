package org.littleshoot.proxy;

/**
 * This exception indicates that the system was asked to use a TransportProtocol that it didn't know how to handle.
 */
public class UnknownTransportProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnknownTransportProtocolException(TransportProtocol transportProtocol) {
        super(String.format("Unknown TransportProtocol: %1$s", transportProtocol));
    }
}
