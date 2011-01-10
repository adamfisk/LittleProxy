package org.littleshoot.proxy;

import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP proxy server.
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final ChannelGroup allChannels = 
        new DefaultChannelGroup("HTTP-Proxy-Server");
            
    private final int port;
    
    private final ProxyAuthorizationManager authenticationManager =
        new DefaultProxyAuthorizationManager();

    private final Map<String, HttpFilter> filters;
    
    private final String chainProxyHostAndPort;

    private final KeyStoreManager ksm;

    private final HttpRequestFilter requestFilter;

    private Runnable stopper;

    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     */
    public DefaultHttpProxyServer(final int port) {
        this(port, new HashMap<String, HttpFilter>());
    }
    
    public DefaultHttpProxyServer(final int port, 
        final Map<String, HttpFilter> filters) {
        this(port, filters, null, null, null);
    }
    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param filters HTTP filters to apply.
     */
    public DefaultHttpProxyServer(final int port, 
        final Map<String, HttpFilter> filters,
        final String chainProxyHostAndPort, final KeyStoreManager ksm,
        final HttpRequestFilter requestFilter) {
        this.port = port;
        this.ksm = ksm;
        this.requestFilter = requestFilter;
        this.filters = Collections.unmodifiableMap(filters);
        this.chainProxyHostAndPort = chainProxyHostAndPort;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            public void uncaughtException(final Thread t, final Throwable e) {
                log.error("Uncaught throwable", e);
            }
        });
    }
    
    public void start() {
        start(false, true);
    }
    
    public void start(final boolean localOnly, final boolean anyAddress) {
        log.info("Starting proxy on port: "+this.port);
        final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));

        final HttpServerPipelineFactory factory = 
            new HttpServerPipelineFactory(authenticationManager, 
                this.allChannels, this.filters, this.chainProxyHostAndPort, 
                ksm, this.requestFilter);
        bootstrap.setPipelineFactory(factory);
        
        // Binding only to localhost can significantly improve the security of
        // the proxy.
        InetSocketAddress isa;
        if (localOnly) {
            isa = new InetSocketAddress("127.0.0.1", port);
        }
        else if (anyAddress) {
            isa = new InetSocketAddress(port);
        } else {
            try {
                isa = new InetSocketAddress(NetworkUtils.getLocalHost(), port);
            } catch (final UnknownHostException e) {
                log.error("Could not get local host?", e);
                isa = new InetSocketAddress(port);
            }
        }
        final Channel channel = bootstrap.bind(isa);
        allChannels.add(channel);

        stopper = new Runnable() {
            public void run() {
                log.info("Shutting down proxy");
                final ChannelGroupFuture future = allChannels.close();
                future.awaitUninterruptibly(120 * 1000);
                bootstrap.releaseExternalResources();
                log.info("Done shutting down proxy");
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(stopper));

        /*
        final ServerBootstrap sslBootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));
        sslBootstrap.setPipelineFactory(new HttpsServerPipelineFactory());
        sslBootstrap.bind(new InetSocketAddress("127.0.0.1", 8443));
        */
    }

    public void addProxyAuthenticationHandler(
        final ProxyAuthorizationHandler pah) {
        this.authenticationManager.addHandler(pah);
    }

    /** {@inheritDoc} */
    public void stop () {
        stopper.run();
    }

    public KeyStoreManager getKeyStoreManager() {
        return this.ksm;
    }
}
