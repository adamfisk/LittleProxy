package org.littleshoot.proxy;

/**
 * Enumeration of transport protocols supported by LittleProxy.
 *
 * UDT support is deprecated in Netty, so it's being deprecated here, too.  We'll remove it when Netty removes it.
 */
public enum TransportProtocol {
    TCP, @Deprecated UDT
}