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

import javax.net.ssl.SSLEngine;

import org.apache.commons.io.IOUtils;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersSource;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SslEngineSource;
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
    private final InetSocketAddress address;
    private final SslEngineSource sslEngineSource;
    private final boolean authenticateSslClients;
    private final ProxyAuthenticator proxyAuthenticator;
    private final ChainedProxyManager chainProxyManager;
    private final MitmManager mitmManager;
    private final HttpFiltersSource filtersSource;
    private final boolean useDnsSec;
    private final boolean transparent;
    private volatile int idleConnectionTimeout;

    /**
     * Track all ActivityTrackers for tracking proxying activity.
     */
    private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<ActivityTracker>();

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

    private DefaultHttpProxyServer(String name,
            TransportProtocol transportProtocol,
            InetSocketAddress address,
            SslEngineSource sslEngineSource,
            boolean authenticateSslClients,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            MitmManager mitmManager,
            HttpFiltersSource filterSource,
            boolean useDnsSec,
            boolean transparent,
            int idleConnectionTimeout,
            Collection<ActivityTracker> activityTrackers) {
        this(new ServerGroup(name), transportProtocol, address,
                sslEngineSource, authenticateSslClients, proxyAuthenticator,
                chainProxyManager,
                mitmManager, filterSource, useDnsSec, transparent,
                idleConnectionTimeout, activityTrackers);
    }

    /**
     * Creates a new proxy server.
     * 
     * @param serverGroup
     *            our ServerGroup for shared thread pools and such
     * @param transportProtocol
     *            The protocol to use for data transport
     * @param port
     *            The port the server should run on.
     * @param transportProtocol
     *            The protocol to use for data transport
     * @param address
     *            The address on which this server will listen
     * @param sslEngineSource
     *            (optional) if specified, this Proxy will encrypt inbound
     *            connections from clients using an {@link SSLEngine} obtained
     *            from this {@link SslEngineSource}.
     * @param authenticateSslClients
     *            Indicate whether or not to authenticate clients when using SSL
     * @param proxyAuthenticator
     *            (optional) If specified, requests to the proxy will be
     *            authenticated using HTTP BASIC authentication per the provided
     *            {@link ProxyAuthenticator}
     * @param chainProxyManager
     *            The proxy to send requests to if chaining proxies. Typically
     *            <code>null</code>.
     * @param mitmManager
     *            The {@link MitmManager} to use for man in the middle'ing
     *            CONNECT requests
     * @param filtersSource
     *            Source for {@link HttpFilters}
     * @param useDnsSec
     *            (optional) Enables the use of secure DNS lookups for outbound
     *            connections.
     * @param transparent
     *            If true, this proxy will run as a transparent proxy (not
     *            touching requests and responses).
     * @param idleConnectionTimeout
     *            The timeout (in seconds) for auto-closing idle connections.
     * @param activityTrackers
     *            for tracking activity on this proxy
     */
    private DefaultHttpProxyServer(ServerGroup serverGroup,
            TransportProtocol transportProtocol,
            InetSocketAddress address,
            SslEngineSource sslEngineSource,
            boolean authenticateSslClients,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            MitmManager mitmManager,
            HttpFiltersSource filtersSource,
            boolean useDnsSec,
            boolean transparent,
            int idleConnectionTimeout,
            Collection<ActivityTracker> activityTrackers) {
        this.serverGroup = serverGroup;
        this.transportProtocol = transportProtocol;
        this.address = address;
        this.sslEngineSource = sslEngineSource;
        this.authenticateSslClients = authenticateSslClients;
        this.proxyAuthenticator = proxyAuthenticator;
        this.chainProxyManager = chainProxyManager;
        this.mitmManager = mitmManager;
        this.filtersSource = filtersSource;
        this.useDnsSec = useDnsSec;
        this.transparent = transparent;
        this.idleConnectionTimeout = idleConnectionTimeout;
        if (activityTrackers != null) {
            this.activityTrackers.addAll(activityTrackers);
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

    @Override
    public HttpProxyServerBootstrap clone() {
        return new DefaultHttpProxyServerBootstrap(this, transportProtocol,
                new InetSocketAddress(address.getAddress(),
                        address.getPort() + 1),
                sslEngineSource, authenticateSslClients, proxyAuthenticator,
                chainProxyManager,
                mitmManager, filtersSource, useDnsSec, transparent,
                idleConnectionTimeout, activityTrackers);
    }

    @Override
    public void stop() {
        serverGroup.stop();
    }

    private HttpProxyServer start() {
        LOG.info("Starting proxy at address: " + this.address);

        synchronized (serverGroup) {
            if (!serverGroup.stopped) {
                doStart();
            } else {
                throw new Error("Already stopped");
            }
        }

        return this;
    }

    private void doStart() {
        ServerBootstrap serverBootstrap = new ServerBootstrap().group(
                serverGroup.clientToProxyBossPools.get(transportProtocol),
                serverGroup.clientToProxyWorkerPools.get(transportProtocol));

        ChannelInitializer<Channel> initializer = new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) throws Exception {
                new ClientToProxyConnection(
                        DefaultHttpProxyServer.this,
                        sslEngineSource,
                        authenticateSslClients,
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
        serverBootstrap.bind(address).addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future)
                    throws Exception {
                registerChannel(future.channel());
            }
        }).awaitUninterruptibly();
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

    protected MitmManager getMitmManager() {
        return mitmManager;
    }

    protected SslEngineSource getSslEngineSource() {
        return sslEngineSource;
    }

    protected ProxyAuthenticator getProxyAuthenticator() {
        return proxyAuthenticator;
    }

    public HttpFiltersSource getFiltersSource() {
        return filtersSource;
    }

    protected Collection<ActivityTracker> getActivityTrackers() {
        return activityTrackers;
    }

    protected EventLoopGroup getProxyToServerWorkerFor(
            TransportProtocol transportProtocol) {
        return this.serverGroup.proxyToServerWorkerPools.get(transportProtocol);
    }

    /**
     * Represents a group of servers that share thread pools.
     */
    private static class ServerGroup {
        private static final int INCOMING_ACCEPTOR_THREADS = 2;
        private static final int INCOMING_WORKER_THREADS = 8;
        private static final int OUTGOING_WORKER_THREADS = 8;

        /**
         * A name for this ServerGroup to use in naming threads.
         */
        private final String name;

        /**
         * Keep track of all channels for later shutdown.
         */
        private final ChannelGroup allChannels = new DefaultChannelGroup(
                "HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE);

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

        private ServerGroup(String name) {
            this.name = name;
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

                NioEventLoopGroup inboundAcceptorGroup = new NioEventLoopGroup(
                        INCOMING_ACCEPTOR_THREADS,
                        new CategorizedThreadFactory("ClientToProxyAcceptor"),
                        selectorProvider);
                NioEventLoopGroup inboundWorkerGroup = new NioEventLoopGroup(
                        INCOMING_WORKER_THREADS,
                        new CategorizedThreadFactory("ClientToProxyWorker"),
                        selectorProvider);
                inboundWorkerGroup.setIoRatio(90);
                NioEventLoopGroup outboundWorkerGroup = new NioEventLoopGroup(
                        OUTGOING_WORKER_THREADS,
                        new CategorizedThreadFactory("ProxyToServerWorker"),
                        selectorProvider);
                outboundWorkerGroup.setIoRatio(90);
                this.clientToProxyBossPools.put(transportProtocol,
                        inboundAcceptorGroup);
                this.clientToProxyWorkerPools.put(transportProtocol,
                        inboundWorkerGroup);
                this.proxyToServerWorkerPools.put(transportProtocol,
                        outboundWorkerGroup);
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

        private class CategorizedThreadFactory implements ThreadFactory {
            private String category;
            private int num = 0;

            public CategorizedThreadFactory(String category) {
                super();
                this.category = category;
            }

            public Thread newThread(final Runnable r) {
                final Thread t = new Thread(r,
                        name + "-" + category + "-" + num++);
                return t;
            }
        }
    }

    private static class DefaultHttpProxyServerBootstrap implements
            HttpProxyServerBootstrap {
        private String name = "LittleProxy";
        private TransportProtocol transportProtocol = TCP;
        private InetSocketAddress address;
        private int port = 8080;
        private boolean allowLocalOnly = true;
        private boolean listenOnAllAddresses = true;
        private SslEngineSource sslEngineSource = null;
        private boolean authenticateSslClients = true;
        private ProxyAuthenticator proxyAuthenticator = null;
        private ChainedProxyManager chainProxyManager = null;
        private MitmManager mitmManager = null;
        private HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter();
        private boolean useDnsSec = false;
        private boolean transparent = false;
        private int idleConnectionTimeout = 70;
        private DefaultHttpProxyServer original;
        private Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<ActivityTracker>();

        private DefaultHttpProxyServerBootstrap() {
        }

        private DefaultHttpProxyServerBootstrap(
                DefaultHttpProxyServer original,
                TransportProtocol transportProtocol,
                InetSocketAddress address,
                SslEngineSource sslEngineSource,
                boolean authenticateSslClients,
                ProxyAuthenticator proxyAuthenticator,
                ChainedProxyManager chainProxyManager,
                MitmManager mitmManager,
                HttpFiltersSource filtersSource, boolean useDnsSec,
                boolean transparent, int idleConnectionTimeout,
                Collection<ActivityTracker> activityTrackers) {
            this.original = original;
            this.transportProtocol = transportProtocol;
            this.address = address;
            this.port = address.getPort();
            this.sslEngineSource = sslEngineSource;
            this.authenticateSslClients = authenticateSslClients;
            this.proxyAuthenticator = proxyAuthenticator;
            this.chainProxyManager = chainProxyManager;
            this.filtersSource = filtersSource;
            this.useDnsSec = useDnsSec;
            this.transparent = transparent;
            this.idleConnectionTimeout = idleConnectionTimeout;
            if (activityTrackers != null) {
                this.activityTrackers.addAll(activityTrackers);
            }
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
        public HttpProxyServerBootstrap withName(String name) {
            this.name = name;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withTransportProtocol(
                TransportProtocol transportProtocol) {
            this.transportProtocol = transportProtocol;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withAddress(InetSocketAddress address) {
            this.address = address;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withPort(int port) {
            this.port = port;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withAllowLocalOnly(
                boolean allowLocalOnly) {
            this.allowLocalOnly = allowLocalOnly;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withListenOnAllAddresses(
                boolean listenOnAllAddresses) {
            this.listenOnAllAddresses = listenOnAllAddresses;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withSslEngineSource(
                SslEngineSource sslEngineSource) {
            this.sslEngineSource = sslEngineSource;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withAuthenticateSslClients(
                boolean authenticateSslClients) {
            this.authenticateSslClients = authenticateSslClients;
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
            if (this.mitmManager != null) {
                LOG.warn("Enabled proxy chaining with man in the middle.  These are mutually exclusive - man in the middle will be disabled.");
                this.mitmManager = null;
            }
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withManInTheMiddle(
                MitmManager mitmManager) {
            this.mitmManager = mitmManager;
            if (this.chainProxyManager != null) {
                LOG.warn("Enabled man in the middle along with proxy chaining.  These are mutually exclusive - proxy chaining will be disabled.");
                this.chainProxyManager = null;
            }
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withFiltersSource(
                HttpFiltersSource filtersSource) {
            this.filtersSource = filtersSource;
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
        public HttpProxyServerBootstrap plusActivityTracker(
                ActivityTracker activityTracker) {
            activityTrackers.add(activityTracker);
            return this;
        }

        @Override
        public HttpProxyServer start() {
            return build().start();
        }

        private DefaultHttpProxyServer build() {
            if (original != null) {
                return new DefaultHttpProxyServer(original.serverGroup,
                        transportProtocol, determineListenAddress(),
                        original.sslEngineSource, authenticateSslClients,
                        proxyAuthenticator, chainProxyManager, mitmManager,
                        filtersSource, useDnsSec, transparent,
                        idleConnectionTimeout, activityTrackers);
            } else {
                return new DefaultHttpProxyServer(
                        name, transportProtocol, determineListenAddress(),
                        sslEngineSource, authenticateSslClients,
                        proxyAuthenticator, chainProxyManager, mitmManager,
                        filtersSource, useDnsSec, transparent,
                        idleConnectionTimeout, activityTrackers);
            }
        }

        private InetSocketAddress determineListenAddress() {
            if (address != null) {
                return address;
            } else {
                // Binding only to localhost can significantly improve the
                // security of
                // the proxy.
                if (allowLocalOnly) {
                    return new InetSocketAddress("127.0.0.1", port);
                } else if (listenOnAllAddresses) {
                    return new InetSocketAddress(port);
                } else {
                    try {
                        return new InetSocketAddress(
                                NetworkUtils.getLocalHost(), port);
                    } catch (final UnknownHostException e) {
                        LOG.error("Could not get local host?", e);
                        return new InetSocketAddress(port);
                    }
                }
            }
        }
    }
}
