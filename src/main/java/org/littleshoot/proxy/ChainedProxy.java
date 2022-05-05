package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;

import java.net.InetSocketAddress;

/**
 * <p>
 * Encapsulates information needed to connect to a chained proxy.
 * </p>
 * 
 * <p>
 * Sub-classes may wish to extend {@link ChainedProxyAdapter} for sensible
 * defaults.
 * </p>
 */
public interface ChainedProxy extends SslEngineSource {
    /**
     * Return the {@link InetSocketAddress} for connecting to the chained proxy.
     * Returning null indicates that we won't chain.
     * 
     * @return The Chain Proxy with Host and Port.
     */
    InetSocketAddress getChainedProxyAddress();

    /**
     * (Optional) ensure that the connection is opened from a specific local
     * address (useful when doing NAT traversal).
     */
    InetSocketAddress getLocalAddress();

    /**
     * Tell LittleProxy what kind of TransportProtocol to use to communicate
     * with the chained proxy.
     */
    TransportProtocol getTransportProtocol();

    /**
     * Tell LittleProxy the type of chained proxy that it will be
     * connecting to.  This setting determines what type of requests
     * LittleProxy will use to communicate with the chained proxy.
     * @return the chained proxy type.
     */
    ChainedProxyType getChainedProxyType();

    /**
     * (Optional) implement this method if the chained proxy requires
     * a username.
     * @return the username to send to the chained proxy.
     */
    String getUsername();

    /**
     * (Optional) implement this method if the chained proxy requires
     * a password.
     * @return the password to send to the chained proxy.
     */
    String getPassword();

    /**
     * Implement this method to tell LittleProxy whether or not to encrypt
     * connections to the chained proxy for the given request. If true,
     * LittleProxy will call {@link SslEngineSource#newSslEngine()} to obtain an
     * SSLContext used by the downstream proxy.
     * 
     * @return true of the connection to the chained proxy should be encrypted
     */
    boolean requiresEncryption();

    /**
     * Filters requests on their way to the chained proxy.
     */
    void filterRequest(HttpObject httpObject);

    /**
     * Called to let us know that connecting to this proxy succeeded.
     */
    void connectionSucceeded();

    /**
     * Called to let us know that connecting to this proxy failed.
     * 
     * @param cause
     *            exception that caused this failure (may be null)
     */
    void connectionFailed(Throwable cause);

    /**
     * Called to let us know that we were disconnected.
     */
    void disconnected();
}
