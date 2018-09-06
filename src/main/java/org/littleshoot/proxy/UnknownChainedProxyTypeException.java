package org.littleshoot.proxy;

/**
 * This exception indicates that the system was asked to use an
 * {@link ChainedProxyType} that it didn't know how to handle.
 */
public class UnknownChainedProxyTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    
    public UnknownChainedProxyTypeException(ChainedProxyType chainedProxyType) {
        super(String.format("Unknown %s: %s", ChainedProxyType.class.getSimpleName(), chainedProxyType));
    }
}
