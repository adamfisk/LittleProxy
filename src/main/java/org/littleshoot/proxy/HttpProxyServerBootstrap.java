package org.littleshoot.proxy;

/**
 * Configures and starts an {@link HttpProxyServer}. The HttpProxyServer is
 * built using {@link #start()} or {@link #start(boolean, boolean)}. Sensible
 * defaults are available for all parameters such that {@link #build()} could be
 * called immediately if you wish.
 */
public interface HttpProxyServerBootstrap {

    public abstract HttpProxyServerBootstrap withTransportProtocol(
            TransportProtocol transportProtocol);

    public abstract HttpProxyServerBootstrap withPort(int port);

    public abstract HttpProxyServerBootstrap withSslContextSource(
            SSLContextSource sslContextSource);

    public abstract HttpProxyServerBootstrap withProxyAuthenticator(
            ProxyAuthenticator proxyAuthenticator);

    public abstract HttpProxyServerBootstrap withChainProxyManager(
            ChainedProxyManager chainProxyManager);

    public abstract HttpProxyServerBootstrap withRequestFilter(
            HttpRequestFilter requestFilter);

    public abstract HttpProxyServerBootstrap withResponseFilters(
            HttpResponseFilters responseFilters);

    public abstract HttpProxyServerBootstrap withUseDnsSec(
            boolean useDnsSec);

    public abstract HttpProxyServerBootstrap withTransparent(
            boolean transparent);

    public abstract HttpProxyServerBootstrap withIdleConnectionTimeout(
            int idleConnectionTimeout);

    /**
     * Starts the server.
     * 
     * @param localOnly
     *            If true, the server will only allow connections from the local
     *            computer. This can significantly improve security in some
     *            cases.
     * @param anyAddress
     *            Whether or not to bind to "any" address - 0.0.0.0. This is the
     *            default.
     * @return the newly built and started server
     */
    public abstract HttpProxyServer start(boolean localOnly,
            boolean anyAddress);

    /**
     * Builds and starts the server.
     * 
     * @return the newly built and started server
     */
    public abstract HttpProxyServer start();

}