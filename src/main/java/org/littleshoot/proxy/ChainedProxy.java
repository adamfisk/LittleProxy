package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.ConnectionFlowStep; //Change: @AlmogBaku
import org.littleshoot.proxy.impl.ProxyConnection; //Change: @AlmogBaku

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
     * //Change: @AlmogBaku
     * Implement this method to tell LittleProxy whether or not to use custom ConnectionFlow
     * to the chained proxy for the given request. If true,
     * LittleProxy will call {@link ChainedProxy#customConnectionFlow(ProxyConnection)} to obtain a
     * ConnectionFlow.
     *
     * @return true of the connection to the chained proxy should be used a custom ConnectionFlow
     */
    boolean requiresCustomConnectionFlow();

    /**
     * //Change: @AlmogBaku
     * Returns an {@link ConnectionFlowStep} to use for a server connection from
     * LittleProxy to the client.
     *
     * @return
     */
    ConnectionFlowStep customConnectionFlow(ProxyConnection connection);

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
}
