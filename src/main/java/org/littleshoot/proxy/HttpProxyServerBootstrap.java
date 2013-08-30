package org.littleshoot.proxy;

/**
 * Configures and starts an {@link HttpProxyServer}. The HttpProxyServer is
 * built using {@link #start()} or {@link #start(boolean, boolean)}. Sensible
 * defaults are available for all parameters such that {@link #build()} could be
 * called immediately if you wish.
 */
public interface HttpProxyServerBootstrap {

    HttpProxyServerBootstrap withName(String name);

    HttpProxyServerBootstrap withTransportProtocol(
            TransportProtocol transportProtocol);

    HttpProxyServerBootstrap withPort(int port);

    HttpProxyServerBootstrap withSslContextSource(
            SSLEngineSource sslContextSource);

    HttpProxyServerBootstrap withProxyAuthenticator(
            ProxyAuthenticator proxyAuthenticator);

    HttpProxyServerBootstrap withChainProxyManager(
            ChainedProxyManager chainProxyManager);

    HttpProxyServerBootstrap withFiltersSource(
            HttpFiltersSource filtersSource);

    HttpProxyServerBootstrap withUseDnsSec(
            boolean useDnsSec);

    HttpProxyServerBootstrap withTransparent(
            boolean transparent);

    HttpProxyServerBootstrap withIdleConnectionTimeout(
            int idleConnectionTimeout);

    /**
     * Builds and starts the server.
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
    HttpProxyServer start(boolean localOnly, boolean anyAddress);

    /**
     * Like {@link #start(boolean, boolean)} with <tt>localOnly</tt> and
     * <tt>anyAddress</tt> both <tt>true</tt>.
     * 
     * @return the newly built and started server
     */
    HttpProxyServer start();

}