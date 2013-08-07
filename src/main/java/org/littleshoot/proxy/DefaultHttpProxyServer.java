package org.littleshoot.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP proxy server.
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
    
    private static final int MAXIMUM_SERVER_THREADS = 60;
    
    private static final int MAXIMUM_CLIENT_THREADS = 60;

    private final Logger log = LoggerFactory.getLogger(getClass());

    // TODO: PMW - make sure that using GlobalEventExecutor.INSTANCE is performant
    private final ChannelGroup allChannels = new DefaultChannelGroup(
            "HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE);

    private final int port;

    private final ProxyAuthorizationManager authenticationManager = new DefaultProxyAuthorizationManager();

    private final ChainProxyManager chainProxyManager;

    private final HandshakeHandlerFactory handshakeHandlerFactory;

    private final HttpRequestFilter requestFilter;

    private final ServerBootstrap serverBootstrap;

    private final HttpResponseFilters responseFilters;

    /**
     * This entire server instance needs to use a single boss EventLoopGroup for
     * the server.
     */
    private final EventLoopGroup serverBoss;

    /**
     * This entire server instance needs to use a single worker EventLoopGroup
     * for the server.
     */
    private final EventLoopGroup serverWorker;

    /**
     * This entire server instance needs to use a single client EventLoopGroup
     * for client channels.
     */
    private final EventLoopGroup clientWorker;

    /**
     * Creates a new proxy server.
     * 
     * @param port
     *            The port the server should run on.
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
     * @param port
     *            The port the server should run on.
     * @param responseFilters
     *            The {@link Map} of request domains to match with associated
     *            {@link HttpFilter}s for filtering responses to those requests.
     */
    public DefaultHttpProxyServer(final int port,
            final HttpResponseFilters responseFilters) {
        this(port, responseFilters, null, null, null,
                new NioEventLoopGroup(MAXIMUM_CLIENT_THREADS, CLIENT_THREAD_FACTORY),
                new NioEventLoopGroup(MAXIMUM_SERVER_THREADS, SERVER_THREAD_FACTORY),
                new NioEventLoopGroup(MAXIMUM_SERVER_THREADS, SERVER_THREAD_FACTORY));
    }

    /**
     * Creates a new proxy server.
     * 
     * @param port
     *            The port the server should run on.
     * @param requestFilter
     *            The filter for HTTP requests.
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
     * @param port
     *            The port the server should run on.
     * @param requestFilter
     *            The filter for HTTP requests.
     * @param responseFilters
     *            HTTP filters to apply.
     */
    public DefaultHttpProxyServer(final int port,
            final HttpRequestFilter requestFilter,
            final HttpResponseFilters responseFilters) {
        this(port, responseFilters, null, null, requestFilter,
                new NioEventLoopGroup(MAXIMUM_CLIENT_THREADS, CLIENT_THREAD_FACTORY),
                new NioEventLoopGroup(MAXIMUM_SERVER_THREADS, SERVER_THREAD_FACTORY),
                new NioEventLoopGroup(MAXIMUM_SERVER_THREADS, SERVER_THREAD_FACTORY));
    }

    /**
     * 
     * @param port
     *            The port the server should run on.
     * @param requestFilter
     *            Optional filter for modifying incoming requests. Often
     *            <code>null</code>.
     * @param clientWorker
     *            The EventLoopGroup for creating outgoing channels to external sites.
     * @param serverBoss
     *            The EventLoopGroup for accepting incoming connections 
     * @param serverWorker
     *            The EventLoopGroup for processing incoming connections           
     */
    public DefaultHttpProxyServer(final int port,
            final HttpRequestFilter requestFilter,
            final EventLoopGroup clientWorker,
            final EventLoopGroup serverBoss,
            final EventLoopGroup serverWorker) {
        this(port, new HttpResponseFilters() {
            public HttpFilter getFilter(String hostAndPort) {
                return null;
            }
        }, null, null, requestFilter, clientWorker,
                serverBoss, serverWorker);
    }

    /**
     * Creates a new proxy server.
     * 
     * @param port
     *            The port the server should run on.
     * @param responseFilters
     *            The {@link Map} of request domains to match with associated
     *            {@link HttpFilter}s for filtering responses to those requests.
     * @param chainProxyManager
     *            The proxy to send requests to if chaining proxies. Typically
     *            <code>null</code>.
     * @param ksm
     *            The key manager if running the proxy over SSL.
     * @param requestFilter
     *            Optional filter for modifying incoming requests. Often
     *            <code>null</code>.
     */
    public DefaultHttpProxyServer(final int port,
            final HttpResponseFilters responseFilters,
            final ChainProxyManager chainProxyManager,
            final HandshakeHandlerFactory handshakeHandlerFactory,
            final HttpRequestFilter requestFilter) {
        this(port, responseFilters, chainProxyManager, handshakeHandlerFactory,
                requestFilter,
                new NioEventLoopGroup(MAXIMUM_CLIENT_THREADS, CLIENT_THREAD_FACTORY),
                new NioEventLoopGroup(MAXIMUM_SERVER_THREADS, SERVER_THREAD_FACTORY),
                new NioEventLoopGroup(MAXIMUM_SERVER_THREADS, SERVER_THREAD_FACTORY));
    }

    /**
     * Creates a new proxy server.
     * 
     * @param port
     *            The port the server should run on.
     * @param responseFilters
     *            The {@link Map} of request domains to match with associated
     *            {@link HttpFilter}s for filtering responses to those requests.
     * @param chainProxyManager
     *            The proxy to send requests to if chaining proxies. Typically
     *            <code>null</code>.
     * @param ksm
     *            The key manager if running the proxy over SSL.
     * @param requestFilter
     *            Optional filter for modifying incoming requests. Often
     *            <code>null</code>.
     * 
     * @param clientWorker
     *            The EventLoopGroup for creating outgoing channels to external sites.
     * @param serverBoss
     *            The EventLoopGroup for accepting incoming connections 
     * @param serverWorker
     *            The EventLoopGroup for processing incoming connections           
     */
    public DefaultHttpProxyServer(final int port,
            final HttpResponseFilters responseFilters,
            final ChainProxyManager chainProxyManager,
            final HandshakeHandlerFactory handshakeHandlerFactory,
            final HttpRequestFilter requestFilter,
            final EventLoopGroup clientWorker,
            final EventLoopGroup serverBoss,
            final EventLoopGroup serverWorker) {
        this.port = port;
        this.responseFilters = responseFilters;
        this.handshakeHandlerFactory = handshakeHandlerFactory;
        this.requestFilter = requestFilter;
        this.chainProxyManager = chainProxyManager;
        this.clientWorker = clientWorker;
        this.serverBoss = serverBoss;
        this.serverWorker = serverWorker;
        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            public void uncaughtException(final Thread t, final Throwable e) {
                log.error("Uncaught throwable", e);
            }
        });

        // Use our thread names so users know there are LittleProxy threads.
        // TODO: PMW - make sure our thread names are still good despite having discontinued use of this API
//        ThreadRenamingRunnable
//                .setThreadNameDeterminer(ThreadNameDeterminer.CURRENT);
        this.serverBootstrap = new ServerBootstrap().group(serverBoss, serverWorker);
    }

    public void start() {
        start(false, true);
    }

    public void start(final boolean localOnly, final boolean anyAddress) {
        log.info("Starting proxy on port: " + this.port);
        this.stopped.set(false);
        final HttpServerChannelInitializer initializer = new HttpServerChannelInitializer(
                authenticationManager, this.allChannels,
                this.chainProxyManager, this.handshakeHandlerFactory,
                new DefaultRelayChannelInitializerFactory(chainProxyManager,
                        this.responseFilters, this.requestFilter,
                        this.allChannels),
                this.clientWorker);
        serverBootstrap.channel(NioServerSocketChannel.class);
        serverBootstrap.childHandler(initializer);
        

        // Binding only to localhost can significantly improve the security of
        // the proxy.
        InetSocketAddress isa;
        if (localOnly) {
            isa = new InetSocketAddress("127.0.0.1", port);
        } else if (anyAddress) {
            isa = new InetSocketAddress(port);
        } else {
            try {
                isa = new InetSocketAddress(NetworkUtils.getLocalHost(), port);
            } catch (final UnknownHostException e) {
                log.error("Could not get local host?", e);
                isa = new InetSocketAddress(port);
            }
        }
        
        serverBootstrap.bind(isa).addListener(new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future)
                    throws Exception {
                allChannels.add(future.channel());
            }
        });

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                stop();
            }
        }));

        /*
         * final ServerBootstrap sslBootstrap = new ServerBootstrap( new
         * NioServerSocketChannelFactory( newServerThreadPool(),
         * newServerThreadPool())); sslBootstrap.setPipelineFactory(new
         * HttpsServerPipelineFactory()); sslBootstrap.bind(new
         * InetSocketAddress("127.0.0.1", 8443));
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
        future.awaitUninterruptibly(10 * 1000);

        if (!future.isSuccess()) {
            final Iterator<ChannelFuture> iter = future.iterator();
            while (iter.hasNext()) {
                final ChannelFuture cf = iter.next();
                if (!cf.isSuccess()) {
                    log.warn("Cause of failure for {} is {}", cf .channel(),
                            cf.cause());
                }
            }
        }
        
        serverBoss.shutdownGracefully();
        serverWorker.shutdownGracefully();
        clientWorker.shutdownGracefully();
        for (EventLoopGroup group : new EventLoopGroup[] {
                serverBoss,
                serverWorker,
                clientWorker}) {
            try {
                group.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                // ignore
            }
        }

        log.info("Done shutting down proxy");
    }

    public void addProxyAuthenticationHandler(
            final ProxyAuthorizationHandler pah) {
        this.authenticationManager.addHandler(pah);
    }

    private static final ThreadFactory CLIENT_THREAD_FACTORY = new ThreadFactory() {

        private int num = 0;

        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(r,
                    "LittleProxy-NioClientSocketChannelFactory-Thread-" + num++);
            return t;
        }
    };

    private static final ThreadFactory SERVER_THREAD_FACTORY = new ThreadFactory() {

        private int num = 0;

        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(r,
                    "LittleProxy-NioServerSocketChannelFactory-Thread-" + num++);
            return t;
        }
    };

}
