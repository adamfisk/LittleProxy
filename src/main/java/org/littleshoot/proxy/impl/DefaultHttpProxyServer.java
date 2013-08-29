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

import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFilter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
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

    private static final Logger LOG = LoggerFactory
            .getLogger(DefaultHttpProxyServer.class);

    /**
     * Our {@link ServerGroup}. Multiple proxy servers can share the same
     * ServerGroup in order to reuse threads and other such resources.
     */
    private final ServerGroup serverGroup;

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

    /**
     * Bootstrap a new {@link DefaultHttpProxyServer} starting from scratch.
     * 
     * @return
     */
    public static HttpProxyServerBootstrap bootstrap() {
        return new DefaultHttpProxyServerBootstrap();
    }

    /**
     * Bootstrap a new {@link DefaultHttpProxyServer} using defaults from the
     * given file.
     * 
     * @param path
     * @return
     */
    public static HttpProxyServerBootstrap bootstrapFromFile(String path) {
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
    private DefaultHttpProxyServer(TransportProtocol transportProtocol,
            int port,
            SSLContextSource sslContextSource,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            HttpRequestFilter requestFilter,
            HttpResponseFilters responseFilters,
            boolean useDnsSec,
            boolean transparent,
            int idleConnectionTimeout) {
        this(new ServerGroup(), transportProtocol, port, sslContextSource,
                proxyAuthenticator, chainProxyManager, requestFilter,
                responseFilters, useDnsSec, transparent, idleConnectionTimeout);
    }

    private DefaultHttpProxyServer(ServerGroup serverGroup,
            TransportProtocol transportProtocol,
            int port,
            SSLContextSource sslContextSource,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            HttpRequestFilter requestFilter,
            HttpResponseFilters responseFilters,
            boolean useDnsSec,
            boolean transparent,
            int idleConnectionTimeout) {
        this.serverGroup = serverGroup;
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

    /**
     * Add an ActivityTracker for tracking proxying activity.
     * 
     * @param activityTracker
     * @return this HttpProxyServer for call chaining
     */
    public HttpProxyServer addActivityTracker(ActivityTracker activityTracker) {
        this.serverGroup.activityTrackers.add(activityTracker);
        return this;
    }

    @Override
    public HttpProxyServerBootstrap clone() {
        return new DefaultHttpProxyServerBootstrap(transportProtocol, port + 1,
                sslContextSource, proxyAuthenticator, chainProxyManager,
                requestFilter, responseFilters, useDnsSec, transparent,
                idleConnectionTimeout);
    }

    @Override
    public void stop() {
        serverGroup.stop();
    }

    private HttpProxyServer start(boolean localOnly, boolean anyAddress) {
        LOG.info("Starting proxy on port: " + this.port);

        synchronized (serverGroup) {
            if (!serverGroup.stopped) {
                doStart(localOnly, anyAddress);
            } else {
                throw new Error("Already stopped");
            }
        }

        return this;
    }

    private void doStart(final boolean localOnly,
            final boolean anyAddress) {
        ServerBootstrap serverBootstrap = new ServerBootstrap().group(
                serverGroup.clientToProxyBossPools.get(transportProtocol),
                serverGroup.clientToProxyWorkerPools.get(transportProtocol));

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
                registerChannel(future.channel());
            }
        });
    }

    /**
     * Register a new {@link Channel} with this server, for later closing.
     * 
     * @param channel
     */
    protected void registerChannel(Channel channel) {
        this.serverGroup.allChannels.add(channel);
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
        return serverGroup.activityTrackers;
    }

    protected EventLoopGroup getProxyToServerWorkerFor(
            TransportProtocol transportProtocol) {
        return this.serverGroup.proxyToServerWorkerPools.get(transportProtocol);
    }

    /**
     * Represents a group of servers that share thread pools.
     */
    private static class ServerGroup {
        private static final int MAXIMUM_INCOMING_THREADS = 10;
        private static final int MAXIMUM_OUTGOING_THREADS = 40;

        /**
         * Keep track of all channels for later shutdown.
         */
        private final ChannelGroup allChannels = new DefaultChannelGroup(
                "HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE);

        /**
         * Track all ActivityTrackers for tracking proxying activity.
         */
        private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<ActivityTracker>();

        /**
         * These {@link EventLoopGroup}s accept incoming connections to the
         * proxies. A different EventLoopGroup is used for each
         * TransportProtocol, since these have to be configured differently.
         */
        private final Map<TransportProtocol, EventLoopGroup> clientToProxyBossPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

        /**
         * These {@link EventLoopGroup}s process incoming requests to the
         * proxies. A different EventLoopGroup is used for each
         * TransportProtocol, since these have to be configured differently.
         */
        private final Map<TransportProtocol, EventLoopGroup> clientToProxyWorkerPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

        /**
         * These {@link EventLoopGroup}s are used for making outgoing
         * connections to servers. A different EventLoopGroup is used for each
         * TransportProtocol, since these have to be configured differently.
         */
        private final Map<TransportProtocol, EventLoopGroup> proxyToServerWorkerPools = new ConcurrentHashMap<TransportProtocol, EventLoopGroup>();

        private volatile boolean stopped = false;

        private ServerGroup() {
            // Set up worker pools for each transport protocol
            for (TransportProtocol transportProtocol : TransportProtocol
                    .values()) {
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

                this.clientToProxyBossPools.put(transportProtocol,
                        new NioEventLoopGroup(
                                MAXIMUM_INCOMING_THREADS,
                                CLIENT_TO_PROXY_THREAD_FACTORY,
                                selectorProvider));
                this.clientToProxyWorkerPools.put(transportProtocol,
                        new NioEventLoopGroup(
                                MAXIMUM_INCOMING_THREADS,
                                CLIENT_TO_PROXY_THREAD_FACTORY,
                                selectorProvider));
                this.proxyToServerWorkerPools.put(
                        transportProtocol,
                        new NioEventLoopGroup(
                                MAXIMUM_OUTGOING_THREADS,
                                PROXY_TO_SERVER_THREAD_FACTORY,
                                selectorProvider));
            }

            Thread.setDefaultUncaughtExceptionHandler(new UncaughtExceptionHandler() {
                public void uncaughtException(final Thread t, final Throwable e) {
                    LOG.error("Uncaught throwable", e);
                }
            });

            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                public void run() {
                    stop();
                }
            }));
        }

        synchronized private void stop() {
            LOG.info("Shutting down proxy");
            if (stopped) {
                LOG.info("Already stopped");
                return;
            }

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
            allEventLoopGroups.addAll(clientToProxyBossPools.values());
            allEventLoopGroups.addAll(clientToProxyWorkerPools.values());
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

            stopped = true;

            LOG.info("Done shutting down proxy");
        }

        private final ThreadFactory CLIENT_TO_PROXY_THREAD_FACTORY = new ThreadFactory() {

            private int num = 0;

            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r,
                        "ClientToProxy-" + num++);
                return t;
            }
        };

        private final ThreadFactory PROXY_TO_SERVER_THREAD_FACTORY = new ThreadFactory() {

            private int num = 0;

            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r,
                        "ProxyToServer-" + num++);
                return t;
            }
        };
    }

    /**
     * Utility for configuring and building an {@link HttpProxyServer}. The
     * HttpProxyServer is built using {@link #build()}. Sensible defaults are
     * available for all parameters such that {@link #build()} could be called
     * immediately if you wish.
     */
    private static class DefaultHttpProxyServerBootstrap implements
            HttpProxyServerBootstrap {
        private TransportProtocol transportProtocol = TCP;
        private int port = 8080;
        private SSLContextSource sslContextSource = null;
        private ProxyAuthenticator proxyAuthenticator = null;
        private ChainedProxyManager chainProxyManager = null;
        private HttpRequestFilter requestFilter = null;
        private HttpResponseFilters responseFilters = null;
        private boolean useDnsSec = false;
        private boolean transparent = false;
        private int idleConnectionTimeout = 70;

        private DefaultHttpProxyServerBootstrap() {
        }

        private DefaultHttpProxyServerBootstrap(
                TransportProtocol transportProtocol, int port,
                SSLContextSource sslContextSource,
                ProxyAuthenticator proxyAuthenticator,
                ChainedProxyManager chainProxyManager,
                HttpRequestFilter requestFilter,
                HttpResponseFilters responseFilters, boolean useDnsSec,
                boolean transparent, int idleConnectionTimeout) {
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
        }

        private DefaultHttpProxyServerBootstrap(Properties props) {
            this.useDnsSec = ProxyUtils.extractBooleanDefaultFalse(
                    props, "dnssec");
            this.transparent = ProxyUtils.extractBooleanDefaultFalse(
                    props, "transparent");
            this.idleConnectionTimeout = ProxyUtils.extractInt(props,
                    "idle_connection_timeout");
        }

        @Override
        public HttpProxyServerBootstrap withTransportProtocol(
                TransportProtocol transportProtocol) {
            this.transportProtocol = transportProtocol;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withPort(int port) {
            this.port = port;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withSslContextSource(
                SSLContextSource sslContextSource) {
            this.sslContextSource = sslContextSource;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withProxyAuthenticator(
                ProxyAuthenticator proxyAuthenticator) {
            this.proxyAuthenticator = proxyAuthenticator;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withChainProxyManager(
                ChainedProxyManager chainProxyManager) {
            this.chainProxyManager = chainProxyManager;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withRequestFilter(
                HttpRequestFilter requestFilter) {
            this.requestFilter = requestFilter;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withResponseFilters(
                HttpResponseFilters responseFilters) {
            this.responseFilters = responseFilters;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withUseDnsSec(boolean useDnsSec) {
            this.useDnsSec = useDnsSec;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withTransparent(
                boolean transparent) {
            this.transparent = transparent;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withIdleConnectionTimeout(
                int idleConnectionTimeout) {
            this.idleConnectionTimeout = idleConnectionTimeout;
            return this;
        }

        @Override
        public HttpProxyServer start(boolean localOnly,
                boolean anyAddress) {
            DefaultHttpProxyServer server = new DefaultHttpProxyServer(
                    transportProtocol, port, sslContextSource,
                    proxyAuthenticator, chainProxyManager,
                    requestFilter, responseFilters, useDnsSec,
                    transparent, idleConnectionTimeout);
            server.start(localOnly, anyAddress);
            return server;
        }

        @Override
        public HttpProxyServer start() {
            return start(true, true);
        }
    }
}
