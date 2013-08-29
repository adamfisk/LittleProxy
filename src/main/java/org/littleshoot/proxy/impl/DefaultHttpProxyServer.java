package org.littleshoot.proxy.impl;

import static org.littleshoot.proxy.TransportProtocol.*;
import io.netty.bootstrap.ChannelFactory;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.ChannelGroupFuture;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.util.concurrent.GlobalEventExecutor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.Thread.UncaughtExceptionHandler;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpRequestFilter;
import org.littleshoot.proxy.HttpResponseFilters;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SSLContextSource;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * Primary implementation of an {@link HttpProxyServer}.
 * </p>
 * 
 * <p>
 * {@link DefaultHttpProxyServer} is bootstrapped by calling
 * {@link #bootstrap()} or {@link #bootstrapFromFile(String)}, and then calling
 * {@link DefaultHttpProxyServerBootstrap#start()} or
 * {@link DefaultHttpProxyServerBootstrap#start(boolean, boolean)}. For example:
 * </p>
 * 
 * <pre>
 * DefaultHttpProxyServer server =
 *         DefaultHttpProxyServer
 *                 .bootstrap()
 *                 .withPort(8090)
 *                 .start();
 * </pre>
 * 
 */
public class DefaultHttpProxyServer implements HttpProxyServer {

    private static final int MAXIMUM_INCOMING_THREADS = 10;

    private static final int MAXIMUM_OUTGOING_THREADS = 40;

    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultHttpProxyServer.class);

    private final ChannelGroup allChannels = new DefaultChannelGroup(
            "HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE);

    private final String name;
    private final TransportProtocol transportProtocol;
    private final int port;
    private final SSLContextSource sslContextSource;
    private final ProxyAuthenticator proxyAuthenticator;
    private final ChainedProxyManager chainProxyManager;
    private final HttpRequestFilter requestFilter;
    private final HttpResponseFilters responseFilters;
    private final boolean useDnsSec;
    private final boolean transparent;
    private volatile int idleConnectionTimeout;
    private final ServerBootstrap serverBootstrap;
    private final EventLoopGroup clientToProxyBossPool;
    private final EventLoopGroup clientToProxyWorkerPool;

    /**
     * These EventLoopGroups are used for making outgoing connections to
     * servers. A different EventLoopGroup is used for each TransportProtocol,
     * since these have to be configured differently.
     */
    private final Map<TransportProtocol, EventLoopGroup> proxyToServerWorkerPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

    /**
     * Track all ActivityTrackers that track proxying activity.
     */
    private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<ActivityTracker>();

    /**
     * Bootstrap a new {@link DefaultHttpProxyServer} starting from scratch.
     * 
     * @return
     */
    public static DefaultHttpProxyServerBootstrap bootstrap() {
        return new DefaultHttpProxyServerBootstrap();
    }

    /**
     * Bootstrap a new {@link DefaultHttpProxyServer} using defaults from the
     * given file.
     * 
     * @param path
     * @return
     */
    public static DefaultHttpProxyServerBootstrap bootstrapFromFile(String path) {
        final File propsFile = new File(path);
        Properties props = new Properties();

        if (propsFile.isFile()) {
            InputStream is = null;
            try {
                is = new FileInputStream(propsFile);
                props.load(is);
            } catch (final IOException e) {
                LOG.warn("Could not load props file?", e);
            } finally {
                IOUtils.closeQuietly(is);
            }
        }

        return new DefaultHttpProxyServerBootstrap(props);
    }

    /**
     * @param name
     *            The name of this proxy server (used for logging)
     * @param transportProtocol
     *            The protocol to use for data transport
     * @param port
     *            The port the server should run on.
     * @param requestFilter
     *            Optional filter for modifying incoming requests. Often
     *            <code>null</code>. public DefaultHttpProxyServer(final
     *            TransportProtocol transportProtocol, final int port, final
     *            HttpRequestFilter requestFilter) { this(transportProtocol,
     *            port, new HttpResponseFilters() { public HttpFilter
     *            getFilter(String hostAndPort) { return null; } }, null, null,
     *            requestFilter); }
     * 
     *            /** Creates a new proxy server.
     * 
     * @param transportProtocol
     *            The protocol to use for data transport
     * @param port
     *            The port the server should run on.
     * @param sslContextSource
     *            (optional) if specified, this Proxy will encrypt inbound
     *            connections from clients using an {@link SSLContext} obtained
     *            from this {@link SSLContextSource}.
     * @param proxyAuthenticator
     *            (optional) If specified, requests to the proxy will be
     *            authenticated using HTTP BASIC authentication per the provided
     *            {@link ProxyAuthenticator}
     * @param chainProxyManager
     *            The proxy to send requests to if chaining proxies. Typically
     *            <code>null</code>.
     * @param requestFilter
     *            Optional filter for modifying incoming requests. Often
     *            <code>null</code>.
     * @param responseFilters
     *            The {@link Map} of request domains to match with associated
     *            {@link HttpFilter}s for filtering responses to those requests.
     * @param useDnsSec
     *            (optional) Enables the use of secure DNS lookups for outbound
     *            connections.
     * @param transparent
     *            If true, this proxy will run as a transparent proxy (not
     *            touching requests and responses).
     * @param idleConnectionTimeout
     *            The timeout (in seconds) for auto-closing idle connections.
     */
    private DefaultHttpProxyServer(String name,
            TransportProtocol transportProtocol,
            int port,
            SSLContextSource sslContextSource,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            HttpRequestFilter requestFilter,
            HttpResponseFilters responseFilters,
            boolean useDnsSec,
            boolean acceptAllSSLCertificates,
            boolean transparent,
            int idleConnectionTimeout) {
        this.name = name;
        this.transportProtocol = transportProtocol;
        this.port = port;
        this.sslContextSource = sslContextSource;
        this.proxyAuthenticator = proxyAuthenticator;
        this.chainProxyManager = chainProxyManager;
        this.requestFilter = requestFilter;
        this.responseFilters = responseFilters;
        this.useDnsSec = useDnsSec;
        this.transparent = transparent;
        this.idleConnectionTimeout = idleConnectionTimeout;

        SelectorProvider selectorProvider = null;
        switch (transportProtocol) {
        case TCP:
            selectorProvider = SelectorProvider.provider();
            break;
        case UDT:
            selectorProvider = NioUdtProvider.BYTE_PROVIDER;
            break;
        default:
            throw new UnknownTransportProtocolError(transportProtocol);
        }

        this.clientToProxyBossPool = new NioEventLoopGroup(
                MAXIMUM_INCOMING_THREADS,
                CLIENT_TO_PROXY_THREAD_FACTORY, selectorProvider);
        this.clientToProxyWorkerPool = new NioEventLoopGroup(
                MAXIMUM_INCOMING_THREADS,
                CLIENT_TO_PROXY_THREAD_FACTORY, selectorProvider);
        this.proxyToServerWorkerPools.put(TCP, new NioEventLoopGroup(
                MAXIMUM_OUTGOING_THREADS,
                PROXY_TO_SERVER_THREAD_FACTORY, SelectorProvider.provider()));
        this.proxyToServerWorkerPools.put(UDT, new NioEventLoopGroup(
                MAXIMUM_OUTGOING_THREADS,
                PROXY_TO_SERVER_THREAD_FACTORY, NioUdtProvider.BYTE_PROVIDER));

        Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
            public void uncaughtException(final Thread t, final Throwable e) {
                LOG.error("Uncaught throwable", e);
            }
        });

        // Use our thread names so users know there are LittleProxy threads.
        this.serverBootstrap = new ServerBootstrap().group(
                clientToProxyBossPool,
                clientToProxyWorkerPool);
    }

    public boolean isUseDnsSec() {
        return useDnsSec;
    }

    public boolean isTransparent() {
        return transparent;
    }

    public int getIdleConnectionTimeout() {
        return idleConnectionTimeout;
    }

    public void setIdleConnectionTimeout(int idleConnectionTimeout) {
        this.idleConnectionTimeout = idleConnectionTimeout;
    }

    @Override
    public HttpProxyServer addActivityTracker(ActivityTracker activityTracker) {
        this.activityTrackers.add(activityTracker);
        return this;
    }

    private HttpProxyServer start(final boolean localOnly,
            final boolean anyAddress) {
        LOG.info("Starting proxy on port: " + this.port);
        this.stopped.set(false);
        ChannelInitializer<Channel> initializer = new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) throws Exception {
                SSLContext sslContext = null;
                if (sslContextSource != null) {
                    sslContext = sslContextSource.getSSLContext();
                }
                new ClientToProxyConnection(
                        DefaultHttpProxyServer.this,
                        sslContext,
                        ch.pipeline());
            };
        };
        switch (transportProtocol) {
        case TCP:
            LOG.info("Proxy listening with TCP transport");
            serverBootstrap.channelFactory(new ChannelFactory<ServerChannel>() {
                @Override
                public ServerChannel newChannel() {
                    return new NioServerSocketChannel();
                }
            });
            break;
        case UDT:
            LOG.info("Proxy listening with UDT transport");
            serverBootstrap.channelFactory(NioUdtProvider.BYTE_ACCEPTOR)
                    .option(ChannelOption.SO_BACKLOG, 10)
                    .option(ChannelOption.SO_REUSEADDR, true);
            break;
        default:
            throw new UnknownTransportProtocolError(transportProtocol);
        }
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
                LOG.error("Could not get local host?", e);
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

        return this;
    }

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    public void stop() {
        LOG.info("Shutting down proxy");
        if (stopped.get()) {
            LOG.info("Already stopped");
            return;
        }
        stopped.set(true);

        LOG.info("Closing all channels...");

        final ChannelGroupFuture future = allChannels.close();
        future.awaitUninterruptibly(10 * 1000);

        if (!future.isSuccess()) {
            final Iterator<ChannelFuture> iter = future.iterator();
            while (iter.hasNext()) {
                final ChannelFuture cf = iter.next();
                if (!cf.isSuccess()) {
                    LOG.warn(
                            "Unable to close channel.  Cause of failure for {} is {}",
                            cf.channel(),
                            cf.cause());
                }
            }
        }

        LOG.info("Shutting down event loops");
        List<EventLoopGroup> allEventLoopGroups = new ArrayList<EventLoopGroup>();
        allEventLoopGroups.add(clientToProxyBossPool);
        allEventLoopGroups.add(clientToProxyWorkerPool);
        allEventLoopGroups.addAll(proxyToServerWorkerPools.values());
        for (EventLoopGroup group : allEventLoopGroups) {
            group.shutdownGracefully();
        }

        for (EventLoopGroup group : allEventLoopGroups) {
            try {
                group.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                LOG.warn("Interrupted while shutting down event loop");
            }
        }

        LOG.info("Done shutting down proxy");
    }

    /**
     * Register a new {@link Channel} with this server, for later closing.
     * 
     * @param channel
     */
    protected void registerChannel(Channel channel) {
        this.allChannels.add(channel);
    }

    protected ChainedProxyManager getChainProxyManager() {
        return chainProxyManager;
    }

    protected SSLContextSource getSslContextSource() {
        return sslContextSource;
    }

    protected ProxyAuthenticator getProxyAuthenticator() {
        return proxyAuthenticator;
    }

    protected HttpRequestFilter getRequestFilter() {
        return requestFilter;
    }

    protected HttpResponseFilters getResponseFilters() {
        return responseFilters;
    }

    protected Collection<ActivityTracker> getActivityTrackers() {
        return activityTrackers;
    }

    protected EventLoopGroup getProxyToServerWorkerFor(
            TransportProtocol transportProtocol) {
        return this.proxyToServerWorkerPools.get(transportProtocol);
    }

    private final ThreadFactory CLIENT_TO_PROXY_THREAD_FACTORY = new ThreadFactory() {

        private int num = 0;

        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(r,
                    name + "-ClientToProxy-" + num++);
            return t;
        }
    };

    private final ThreadFactory PROXY_TO_SERVER_THREAD_FACTORY = new ThreadFactory() {

        private int num = 0;

        public Thread newThread(final Runnable r) {
            final Thread t = new Thread(r,
                    name + "-ProxyToServer-" + num++);
            return t;
        }
    };

    /**
     * Utility for configuring and building an {@link HttpProxyServer}. The
     * HttpProxyServer is built using {@link #build()}. Sensible defaults are
     * available for all parameters such that {@link #build()} could be called
     * immediately if you wish.
     */
    public static class DefaultHttpProxyServerBootstrap {
        private String name = "LittleProxy";
        private TransportProtocol transportProtocol = TCP;
        private int port = 8080;
        private SSLContextSource sslContextSource = null;
        private ProxyAuthenticator proxyAuthenticator = null;
        private ChainedProxyManager chainProxyManager = null;
        private HttpRequestFilter requestFilter = null;
        private HttpResponseFilters responseFilters = null;
        private boolean useDnsSec = false;
        private boolean acceptAllSSLCertificates = false;
        private boolean transparent = false;
        private int idleConnectionTimeout = 70;

        private DefaultHttpProxyServerBootstrap() {
        }

        private DefaultHttpProxyServerBootstrap(Properties props) {
            this.useDnsSec = ProxyUtils.extractBooleanDefaultFalse(
                    props, "dnssec");
            this.acceptAllSSLCertificates = ProxyUtils
                    .extractBooleanDefaultFalse(props,
                            "accept_all_ssl_certificates");
            this.transparent = ProxyUtils.extractBooleanDefaultFalse(
                    props, "transparent");
            this.idleConnectionTimeout = ProxyUtils.extractInt(props,
                    "idle_connection_timeout");
        }

        public DefaultHttpProxyServerBootstrap withName(String name) {
            this.name = name;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withTransportProtocol(
                TransportProtocol transportProtocol) {
            this.transportProtocol = transportProtocol;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withPort(int port) {
            this.port = port;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withSslContextSource(
                SSLContextSource sslContextSource) {
            this.sslContextSource = sslContextSource;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withProxyAuthenticator(
                ProxyAuthenticator proxyAuthenticator) {
            this.proxyAuthenticator = proxyAuthenticator;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withChainProxyManager(
                ChainedProxyManager chainProxyManager) {
            this.chainProxyManager = chainProxyManager;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withRequestFilter(
                HttpRequestFilter requestFilter) {
            this.requestFilter = requestFilter;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withResponseFilters(
                HttpResponseFilters responseFilters) {
            this.responseFilters = responseFilters;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withUseDnsSec(boolean useDnsSec) {
            this.useDnsSec = useDnsSec;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withAcceptAllSSLCertificates(
                boolean acceptAllSSLCertificates) {
            this.acceptAllSSLCertificates = acceptAllSSLCertificates;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withTransparent(
                boolean transparent) {
            this.transparent = transparent;
            return this;
        }

        public DefaultHttpProxyServerBootstrap withIdleConnectionTimeout(
                int idleConnectionTimeout) {
            this.idleConnectionTimeout = idleConnectionTimeout;
            return this;
        }

        /**
         * Starts the server.
         * 
         * @param localOnly
         *            If true, the server will only allow connections from the
         *            local computer. This can significantly improve security in
         *            some cases.
         * @param anyAddress
         *            Whether or not to bind to "any" address - 0.0.0.0. This is
         *            the default.
         * @return the newly built and started server
         */
        public DefaultHttpProxyServer start(boolean localOnly,
                boolean anyAddress) {
            DefaultHttpProxyServer server = new DefaultHttpProxyServer(
                    name, transportProtocol, port, sslContextSource,
                    proxyAuthenticator, chainProxyManager,
                    requestFilter, responseFilters, useDnsSec,
                    acceptAllSSLCertificates, transparent,
                    idleConnectionTimeout);
            server.start(localOnly, anyAddress);
            return server;
        }

        /**
         * Builds and starts the server.
         * 
         * @return the newly built and started server
         */
        public DefaultHttpProxyServer start() {
            return start(true, true);
        }
    }
}
