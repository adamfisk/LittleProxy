package org.littleshoot.proxy;

import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
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
    
    private final Logger log = 
        LoggerFactory.getLogger(DefaultHttpProxyServer.class);
    
    private final ChannelGroup allChannels = 
        new DefaultChannelGroup("HTTP-Proxy-Server");
            
    private final int port;
    
    private final ProxyAuthorizationManager authenticationManager =
        new DefaultProxyAuthorizationManager();

    private final Map<String, HttpFilter> filters;

    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     */
    public DefaultHttpProxyServer(final int port) {
        this(port, new HashMap<String, HttpFilter>());
    }
    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param filters HTTP filters to apply.
     */
    public DefaultHttpProxyServer(final int port, 
        final Map<String, HttpFilter> filters) {
        this.port = port;
        this.filters = Collections.unmodifiableMap(filters);
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            
            public void uncaughtException(final Thread t, final Throwable e) {
                log.error("Uncaught exception", e);
            }
        });
    }
    

    public void start() {
        log.info("Starting proxy on port: "+this.port);
        final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));

        final HttpServerPipelineFactory factory = 
            new HttpServerPipelineFactory(authenticationManager, 
                this.allChannels, this.filters);
        bootstrap.setPipelineFactory(factory);
        final Channel channel = bootstrap.bind(new InetSocketAddress(port));
        allChannels.add(channel);
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                final ChannelGroupFuture future = allChannels.close();
                future.awaitUninterruptibly(120*1000);
                bootstrap.releaseExternalResources();
            }
        }));
        /*
        final ServerBootstrap sslBootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));
        sslBootstrap.setPipelineFactory(new HttpServerPipelineFactory());
        sslBootstrap.bind(new InetSocketAddress("127.0.0.1", 8443));
        */
    }

    public void addProxyAuthenticationHandler(
        final ProxyAuthorizationHandler pah) {
        this.authenticationManager.addHandler(pah);
    }
}
