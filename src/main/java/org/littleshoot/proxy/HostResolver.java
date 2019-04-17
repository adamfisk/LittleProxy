package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

/**
 * Resolves host and port into an InetSocketAddress.
 */
public interface HostResolver {
    InetSocketAddress resolve(String host, int port) throws UnknownHostException;
}
