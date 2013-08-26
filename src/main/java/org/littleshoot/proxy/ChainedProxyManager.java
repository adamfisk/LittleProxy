package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import javax.net.ssl.SSLContext;

/**
 * Interface for classes that manage chained proxies.
 */
public interface ChainedProxyManager {

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
     * connections to the chained proxy for the given request.
     * 
     * @param httpRequest
     *            The HTTP request.
     * @return true of the connection to the chained proxy should be encrypted
     *         with TLS
     */
    boolean requiresTLSEncryption(HttpRequest httpRequest);

    /**
     * If {@link #requiresTLSEncryption(HttpRequest)} returns true, LittleProxy
     * will call this method to obtain an SSLContext for doing the encryption.
     * 
     * @return
     */
    SSLContext getSSLContext();

    /**
     * Tell LittleProxy what kind of TransportProtocol to use to communicate
     * with the chained proxy.
     * 
     * @return
     */
    TransportProtocol getTransportProtocol();

}