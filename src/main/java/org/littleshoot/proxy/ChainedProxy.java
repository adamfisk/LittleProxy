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
     * 
     * @return
     */
    InetSocketAddress getLocalAddress();

    /**
     * Tell LittleProxy what kind of TransportProtocol to use to communicate
     * with the chained proxy.
     * 
     * @return
     */
    TransportProtocol getTransportProtocol();

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
     * 
     * @param httpObject
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
    
    /**
     * (Optional) user name which is send to the Chain Proxy using basic authentication. The user name
     * is only send if the {@link #getBasicAuthPassword()} method provides a non <code>null</code>
     * string.
     * 
     * @return The user name or <code>null</code> if no authentication to the Chain Proxy is
     *         necessary.
     */
    String getBasicAuthUser();
    
    /**
     * (Optional) user password which is send to the Chain Proxy using basic authentication. The
     * password is only send if the {@link #getBasicAuthUser()} method provides a non
     * <code>null</code> string.
     * 
     * @return The password or <code>null</code> if no authentication to the Chain Proxy is necessary.
     */
    String getBasicAuthPassword();
}
