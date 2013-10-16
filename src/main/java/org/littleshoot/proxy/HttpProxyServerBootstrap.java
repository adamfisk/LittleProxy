package org.littleshoot.proxy;

import java.net.InetSocketAddress;

/**
 * Configures and starts an {@link HttpProxyServer}. The HttpProxyServer is
 * built using {@link #start()}. Sensible defaults are available for all
 * parameters such that {@link #start()} could be called immediately if you
 * wish.
 */
public interface HttpProxyServerBootstrap {

    /**
     * <p>
     * Give the server a name (used for naming threads, useful for logging).
     * </p>
     * 
     * <p>
     * Default = LittleProxy
     * </p>
     * 
     * @param name
     * @return
     */
    HttpProxyServerBootstrap withName(String name);

    /**
     * <p>
     * Specify the {@link TransportProtocol} to use for incoming connections.
     * </p>
     * 
     * <p>
     * Default = TCP
     * </p>
     * 
     * @param transportProtocol
     * @return
     */
    HttpProxyServerBootstrap withTransportProtocol(
            TransportProtocol transportProtocol);

    /**
     * <p>
     * Listen for incoming connections on the given address.
     * </p>
     * 
     * <p>
     * Default = [bound ip]:8080
     * </p>
     * 
     * @param address
     * @return
     */
    HttpProxyServerBootstrap withAddress(InetSocketAddress address);

    /**
     * <p>
     * Listen for incoming connections on the given port.
     * </p>
     * 
     * <p>
     * Default = 8080
     * </p>
     * 
     * @param port
     * @return
     */
    HttpProxyServerBootstrap withPort(int port);

    /**
     * <p>
     * Specify whether or not to only allow local connections.
     * </p>
     * 
     * <p>
     * Default = true
     * </p>
     * 
     * @param allowLocalOnly
     * @return
     */
    HttpProxyServerBootstrap withAllowLocalOnly(boolean allowLocalOnly);

    /**
     * <p>
     * Specify whether or not to listen on all interfaces.
     * </p>
     * 
     * <p>
     * Default = false
     * </p>
     * 
     * @param listenOnAllAddresses
     * @return
     */
    HttpProxyServerBootstrap withListenOnAllAddresses(
            boolean listenOnAllAddresses);

    /**
     * <p>
     * Specify an {@link SslEngineSource} to use for encrypting inbound
     * connections.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * @param sslEngineSource
     * @return
     */
    HttpProxyServerBootstrap withSslEngineSource(
            SslEngineSource sslEngineSource);

    /**
     * <p>
     * Specify whether or not to authenticate inbound SSL clients (only applies
     * if {@link #withSslEngineSource(SslEngineSource)} has been set).
     * </p>
     * 
     * <p>
     * Default = true
     * </p>
     * 
     * @param authenticateSslClients
     * @return
     */
    HttpProxyServerBootstrap withAuthenticateSslClients(
            boolean authenticateSslClients);

    /**
     * <p>
     * Specify a {@link ProxyAuthenticator} to use for doing basic HTTP
     * authentication of clients.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * @param proxyAuthenticator
     * @return
     */
    HttpProxyServerBootstrap withProxyAuthenticator(
            ProxyAuthenticator proxyAuthenticator);

    /**
     * <p>
     * Specify a {@link ChainedProxyManager} to use for chaining requests to
     * another proxy.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * <p>
     * Note - This and {@link #withManInTheMiddle(MitmManager)} are currently
     * mutually exclusive.
     * </p>
     * 
     * @param chainProxyManager
     * @return
     */
    HttpProxyServerBootstrap withChainProxyManager(
            ChainedProxyManager chainProxyManager);

    /**
     * <p>
     * Specify an {@link MitmManager} to use for making this proxy act as an SSL
     * man in the middle
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * <p>
     * Note - This and {@link #withChainProxyManager(ChainedProxyManager)} are
     * currently mutually exclusive.
     * </p>
     * 
     * @param mitmManager
     * @return
     */
    HttpProxyServerBootstrap withManInTheMiddle(
            MitmManager mitmManager);

    /**
     * <p>
     * Specify a {@link HttpFiltersSource} to use for filtering requests and/or
     * responses through this proxy.
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * @param filtersSource
     * @return
     */
    HttpProxyServerBootstrap withFiltersSource(
            HttpFiltersSource filtersSource);

    /**
     * <p>
     * Specify whether or not to use secure DNS lookups for outbound
     * connections.
     * </p>
     * 
     * <p>
     * Default = false
     * </p>
     * 
     * @param useDnsSec
     * @return
     */
    HttpProxyServerBootstrap withUseDnsSec(
            boolean useDnsSec);

    /**
     * <p>
     * Specify whether or not to run this proxy as a transparent proxy.
     * </p>
     * 
     * <p>
     * Default = false
     * </p>
     * 
     * @param transparent
     * @return
     */
    HttpProxyServerBootstrap withTransparent(
            boolean transparent);

    /**
     * <p>
     * Specify the timeout after which to disconnect idle connections, in
     * seconds.
     * </p>
     * 
     * <p>
     * Default = 70
     * </p>
     * 
     * @param idleConnectionTimeout
     * @return
     */
    HttpProxyServerBootstrap withIdleConnectionTimeout(
            int idleConnectionTimeout);

    /**
     * <p>
     * Add an {@link ActivityTracker} for tracking activity in this proxy.
     * </p>
     * 
     * @param activityTracker
     * @return
     */
    HttpProxyServerBootstrap plusActivityTracker(ActivityTracker activityTracker);

    /**
     * <p>
     * Build and starts the server.
     * </p>
     * 
     * @return the newly built and started server
     */
    HttpProxyServer start();

}