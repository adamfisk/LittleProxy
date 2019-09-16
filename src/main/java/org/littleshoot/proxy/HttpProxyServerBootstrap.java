package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.ThreadPoolConfiguration;
import org.littleshoot.proxy.impl.ServerGroup;
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
     */
    HttpProxyServerBootstrap withAllowLocalOnly(boolean allowLocalOnly);

    /**
     * This method has no effect and will be removed in a future release.
     * @deprecated use {@link #withNetworkInterface(InetSocketAddress)} to avoid listening on all local addresses
     */
    @Deprecated
    HttpProxyServerBootstrap withListenOnAllAddresses(boolean listenOnAllAddresses);

    /**
     * <p>
     * Specify an {@link SslEngineSource} to use for encrypting inbound
     * connections. Enabling this will enable SSL client authentication
     * by default (see {@link #withAuthenticateSslClients(boolean)})
     * </p>
     * 
     * <p>
     * Default = null
     * </p>
     * 
     * <p>
     * Note - This and {@link #withManInTheMiddle(MitmManager)} are
     * mutually exclusive.
     * </p>
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
     * Note - This and {@link #withSslEngineSource(SslEngineSource)} are
     * mutually exclusive.
     * </p>
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
     */
    HttpProxyServerBootstrap withIdleConnectionTimeout(
            int idleConnectionTimeout);

    /**
     * <p>
     * Specify the timeout for connecting to the upstream server on a new
     * connection, in milliseconds.
     * </p>
     * 
     * <p>
     * Default = 40000
     * </p>
     */
    HttpProxyServerBootstrap withConnectTimeout(
            int connectTimeout);

    /**
     * Specify a custom {@link HostResolver} for resolving server addresses.
     */
    HttpProxyServerBootstrap withServerResolver(HostResolver serverResolver);

    /**
    * Specify a custom {@link ServerGroup} to use for managing this server's resources and such.
    * If one isn't provided, a default one will be created using the {@link ThreadPoolConfiguration} provided
    * 
    * @param group A custom server group
    */
    HttpProxyServerBootstrap withServerGroup(ServerGroup group);
    
    /**
     * <p>
     * Add an {@link ActivityTracker} for tracking activity in this proxy.
     * </p>
     */
    HttpProxyServerBootstrap plusActivityTracker(ActivityTracker activityTracker);

    /**
     * <p>
     * Specify the read and/or write bandwidth throttles for this proxy server. 0 indicates not throttling.
     * </p>
     */
    HttpProxyServerBootstrap withThrottling(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond);

    /**
     * All outgoing-communication of the proxy-instance is goin' to be routed via the given network-interface
     *
     * @param inetSocketAddress to be used for outgoing communication
     */
    HttpProxyServerBootstrap withNetworkInterface(InetSocketAddress inetSocketAddress);
    
    HttpProxyServerBootstrap withMaxInitialLineLength(int maxInitialLineLength);
    
    HttpProxyServerBootstrap withMaxHeaderSize(int maxHeaderSize);
    
    HttpProxyServerBootstrap withMaxChunkSize(int maxChunkSize);

    /**
     * When true, the proxy will accept requests that appear to be directed at an origin server (i.e. the URI in the HTTP
     * request will contain an origin-form, rather than an absolute-form, as specified in RFC 7230, section 5.3).
     * This is useful when the proxy is acting as a gateway/reverse proxy. <b>Note:</b> This feature should not be
     * enabled when running as a forward proxy; doing so may cause an infinite loop if the client requests the URI of the proxy.
     *
     * @param allowRequestToOriginServer when true, the proxy will accept origin-form HTTP requests
     */
    HttpProxyServerBootstrap withAllowRequestToOriginServer(boolean allowRequestToOriginServer);

    /**
     * Sets the alias to use when adding Via headers to incoming and outgoing HTTP messages. The alias may be any
     * pseudonym, or if not specified, defaults to the hostname of the local machine. See RFC 7230, section 5.7.1.
     *
     * @param alias the pseudonym to add to Via headers
     */
    HttpProxyServerBootstrap withProxyAlias(String alias);

    /**
     * <p>
     * Build and starts the server.
     * </p>
     *
     * @return the newly built and started server
     */
    HttpProxyServer start();

    /**
     * Set the configuration parameters for the proxy's thread pools.
     *
     * @param configuration thread pool configuration
     * @return proxy server bootstrap for chaining
     */
    HttpProxyServerBootstrap withThreadPoolConfiguration(ThreadPoolConfiguration configuration);

    /**
     * Specifies if the proxy server should accept a proxy protocol header. Once set it works with request that
     * include a proxy protocol header. The proxy server reads an incoming proxy protocol header from the
     * client.
     * @param allowProxyProtocol when true, the proxy will accept a proxy protocol header
     */
    HttpProxyServerBootstrap withAcceptProxyProtocol(boolean allowProxyProtocol);

    /**
     * Specifies if the proxy server should send a proxy protocol header.
     * @param sendProxyProtocol when true, the proxy will send a proxy protocol header
     */
    HttpProxyServerBootstrap withSendProxyProtocol(boolean sendProxyProtocol);
}
