package org.littleshoot.proxy;

import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.ServerSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.ThreadNameDeterminer;
import org.jboss.netty.util.ThreadRenamingRunnable;
import org.jboss.netty.util.Timer;
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

    private final ChainProxyManager chainProxyManager;

    private final KeyStoreManager ksm;

    private final HttpRequestFilter requestFilter;

    private final ServerBootstrap serverBootstrap;

    private final HttpResponseFilters responseFilters;
    
    /**
     * This entire server instance needs to use a single timer.
     */
    private final Timer timer;
    
    /**
     * This entire server instance needs to use a single factory for server
     * channels.
     */
    private final ServerSocketChannelFactory serverChannelFactory;
    
    /**
     * This entire server instance needs to use a single factory for client
     * channels.
     */
    private final ClientSocketChannelFactory clientChannelFactory;

    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     */
    public DefaultHttpProxyServer(final int port) {
        this(port, new HttpResponseFilters() {
            public HttpFilter getFilter(String hostAndPort) {
                return null;
            }
        });
    }
    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param responseFilters The {@link Map} of request domains to match 
     * with associated {@link HttpFilter}s for filtering responses to 
     * those requests.
     */
    public DefaultHttpProxyServer(final int port, 
        final HttpResponseFilters responseFilters) {
        this(port, responseFilters, null, null, null, 
            new NioClientSocketChannelFactory(
                newClientThreadPool(),
                newClientThreadPool()), 
            new HashedWheelTimer(),
            new NioServerSocketChannelFactory(
                newServerThreadPool(),
                newServerThreadPool()));
    }

    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param requestFilter The filter for HTTP requests.
     */
    public DefaultHttpProxyServer(final int port, 
        final HttpRequestFilter requestFilter) {
        this(port, requestFilter, new HttpResponseFilters() {
            public HttpFilter getFilter(String hostAndPort) {
                return null;
            }
        });
    }

    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param requestFilter The filter for HTTP requests.
     * @param responseFilters HTTP filters to apply.
     */
    public DefaultHttpProxyServer(final int port,
        final HttpRequestFilter requestFilter,
        final HttpResponseFilters responseFilters) {
        this(port, responseFilters, null, null, requestFilter,
            new NioClientSocketChannelFactory(
                    newClientThreadPool(),
                    newClientThreadPool()), 
            new HashedWheelTimer(),
            new NioServerSocketChannelFactory(
                newServerThreadPool(),
                newServerThreadPool()));
    }
    
    /**
     * 
     * @param port The port the server should run on.
     * @param requestFilter Optional filter for modifying incoming requests.
     * Often <code>null</code>.
     * @param clientChannelFactory The factory for creating outgoing channels
     * to external sites.
     * @param timer The global timer for timing out idle connections.
     * @param serverChannelFactory The factory for creating listening channels
     * for incoming connections.
     */
    public DefaultHttpProxyServer(final int port, 
        final HttpRequestFilter requestFilter,
        final ClientSocketChannelFactory clientChannelFactory, 
        final Timer timer,
        final ServerSocketChannelFactory serverChannelFactory) {
        this(port, new HttpResponseFilters() {
            public HttpFilter getFilter(String hostAndPort) {
                return null;
            }
        }, null, null, requestFilter, clientChannelFactory, timer, 
        serverChannelFactory);
    }
    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param responseFilters The {@link Map} of request domains to match 
     * with associated {@link HttpFilter}s for filtering responses to 
     * those requests.
     * @param chainProxyManager The proxy to send requests to if chaining
     * proxies. Typically <code>null</code>.
     * @param ksm The key manager if running the proxy over SSL.
     * @param requestFilter Optional filter for modifying incoming requests.
     * Often <code>null</code>.
     */
    public DefaultHttpProxyServer(final int port, 
        final HttpResponseFilters responseFilters,
        final ChainProxyManager chainProxyManager, final KeyStoreManager ksm,
        final HttpRequestFilter requestFilter) {
        this(port, responseFilters, chainProxyManager, ksm, requestFilter,
            new NioClientSocketChannelFactory(
                    newClientThreadPool(),
                    newClientThreadPool()), 
            new HashedWheelTimer(),
            new NioServerSocketChannelFactory(
                newServerThreadPool(),
                newServerThreadPool()));
    }
    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param responseFilters The {@link Map} of request domains to match 
     * with associated {@link HttpFilter}s for filtering responses to 
     * those requests.
     * @param chainProxyManager The proxy to send requests to if chaining
     * proxies. Typically <code>null</code>.
     * @param ksm The key manager if running the proxy over SSL.
     * @param requestFilter Optional filter for modifying incoming requests.
     * Often <code>null</code>.
     * 
     * @param clientChannelFactory The factory for creating outgoing channels
     * to external sites.
     * @param timer The global timer for timing out idle connections.
     * @param serverChannelFactory The factory for creating listening channels
     * for incoming connections.
     */
    public DefaultHttpProxyServer(final int port, 
        final HttpResponseFilters responseFilters,
        final ChainProxyManager chainProxyManager, final KeyStoreManager ksm,
        final HttpRequestFilter requestFilter,
        final ClientSocketChannelFactory clientChannelFactory, 
        final Timer timer,
        final ServerSocketChannelFactory serverChannelFactory) {
        this.port = port;
        this.responseFilters = responseFilters;
        this.ksm = ksm;
        this.requestFilter = requestFilter;
        this.chainProxyManager = chainProxyManager;
        this.clientChannelFactory = clientChannelFactory;
        this.timer = timer;
        this.serverChannelFactory = serverChannelFactory;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            public void uncaughtException(final Thread t, final Throwable e) {
                log.error("Uncaught throwable", e);
            }
        });
        
        // Use our thread names so users know there are LittleProxy threads.
        ThreadRenamingRunnable.setThreadNameDeterminer(
                ThreadNameDeterminer.CURRENT);
        this.serverBootstrap = 
            new ServerBootstrap(serverChannelFactory);
    }

    public void start() {
        start(false, true);
    }
    
    public void start(final boolean localOnly, final boolean anyAddress) {
        log.info("Starting proxy on port: "+this.port);
        this.stopped.set(false);
        final HttpServerPipelineFactory factory = 
            new HttpServerPipelineFactory(authenticationManager, 
                this.allChannels, this.chainProxyManager, this.ksm, 
                new DefaultRelayPipelineFactoryFactory(chainProxyManager, 
                    this.responseFilters, this.requestFilter, 
                    this.allChannels, timer), timer, this.clientChannelFactory);
        serverBootstrap.setPipelineFactory(factory);
        
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
        final Channel channel = serverBootstrap.bind(isa);
        allChannels.add(channel);
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                stop();
            }
        }));

        /*
        final ServerBootstrap sslBootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                newServerThreadPool(),
                newServerThreadPool()));
        sslBootstrap.setPipelineFactory(new HttpsServerPipelineFactory());
        sslBootstrap.bind(new InetSocketAddress("127.0.0.1", 8443));
        */
    }
    
    private final AtomicBoolean stopped = new AtomicBoolean(false);
    
    public void stop() {
        log.info("Shutting down proxy");
        if (stopped.get()) {
            log.info("Already stopped");
            return;
        }
        stopped.set(true);
        
        log.info("Closing all channels...");
        
        // See http://static.netty.io/3.5/guide/#start.12
        
        final ChannelGroupFuture future = allChannels.close();
        future.awaitUninterruptibly(10*1000);
        
        if (!future.isCompleteSuccess()) {
            final Iterator<ChannelFuture> iter = future.iterator();
            while (iter.hasNext()) {
                final ChannelFuture cf = iter.next();
                if (!cf.isSuccess()) {
                    log.warn("Cause of failure for {} is {}", cf.getChannel(), cf.getCause());
                }
            }
        }
        log.info("Stopping timer");
        timer.stop();
        serverChannelFactory.releaseExternalResources();
        clientChannelFactory.releaseExternalResources();
        
        log.info("Done shutting down proxy");
    }

    public void addProxyAuthenticationHandler(
        final ProxyAuthorizationHandler pah) {
        this.authenticationManager.addHandler(pah);
    }

    public KeyStoreManager getKeyStoreManager() {
        return this.ksm;
    }
    
    
    private static Executor newClientThreadPool() {
        return Executors.newCachedThreadPool(
            new ThreadFactory() {
                
                private int num = 0;
                public Thread newThread(final Runnable r) {
                    final Thread t = new Thread(r, 
                        "LittleProxy-NioClientSocketChannelFactory-Thread-"+num++);
                    return t;
                }
            });
    }
    
    private static Executor newServerThreadPool() {
        return Executors.newCachedThreadPool(
            new ThreadFactory() {
                
                private int num = 0;
                public Thread newThread(final Runnable r) {
                    final Thread t = new Thread(r, 
                        "LittleProxy-NioServerSocketChannelFactory-Thread-"+num++);
                    return t;
                }
            });
    }

}
