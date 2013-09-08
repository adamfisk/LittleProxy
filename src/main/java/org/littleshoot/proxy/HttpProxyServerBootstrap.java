package org.littleshoot.proxy;

import java.net.InetSocketAddress;

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

    HttpProxyServerBootstrap withAddress(InetSocketAddress address);

    HttpProxyServerBootstrap withPort(int port);

    HttpProxyServerBootstrap withAllowLocalOnly(boolean allowLocalOnly);

    HttpProxyServerBootstrap withListenOnAllAddresses(
            boolean listenOnAllAddresses);

    HttpProxyServerBootstrap withSSLEngineSource(
            SSLEngineSource sslEngineSource);

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

    HttpProxyServerBootstrap plusActivityTracker(ActivityTracker activityTracker);

    /**
     * Builds and starts the server.
     * 
     * @return the newly built and started server
     */
    HttpProxyServer start();

}