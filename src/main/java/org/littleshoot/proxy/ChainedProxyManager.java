package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

/**
 * Interface for classes that manage chained proxies.
 */
public interface ChainedProxyManager extends SSLContextSource {

    /**
     * Return the host and port for the chained proxy to use. Returning null
     * indicates that we won't chain.
     * 
     * @param httpRequest
     *            The HTTP request.
     * @return The Chain Proxy with Host and Port.
     */
    String getHostAndPort(HttpRequest httpRequest);

    /**
     * Implement this method to tell LittleProxy whether or not to encrypt
     * connections to the chained proxy for the given request. If true,
     * LittleProxy will call {@link SSLContextSource#getSSLContext()} to obtain
     * an SSLContext used by the upstream proxy.
     * 
     * @param httpRequest
     *            The HTTP request.
     * @return true of the connection to the chained proxy should be encrypted
     */
    boolean requiresEncryption(HttpRequest httpRequest);

    /**
     * Tell LittleProxy what kind of TransportProtocol to use to communicate
     * with the chained proxy.
     * 
     * @return
     */
    TransportProtocol getTransportProtocol();

}