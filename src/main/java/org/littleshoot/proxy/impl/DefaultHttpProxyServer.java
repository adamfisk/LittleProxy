package org.littleshoot.proxy.impl;

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
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.traffic.GlobalTrafficShapingHandler;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.apache.commons.io.IOUtils;
import org.littleshoot.proxy.ActivityTracker;
import org.littleshoot.proxy.ChainedProxyManager;
import org.littleshoot.proxy.DefaultHostResolver;
import org.littleshoot.proxy.DnsSecServerResolver;
import org.littleshoot.proxy.HostResolver;
import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersSource;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.HttpProxyServerBootstrap;
import org.littleshoot.proxy.MitmManager;
import org.littleshoot.proxy.ProxyAuthenticator;
import org.littleshoot.proxy.SslEngineSource;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLEngine;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <p>
 * Primary implementation of an {@link HttpProxyServer}.
 * </p>
 *
 * <p>
 * {@link DefaultHttpProxyServer} is bootstrapped by calling
 * {@link #bootstrap()} or {@link #bootstrapFromFile(String)}, and then calling
 * {@link DefaultHttpProxyServerBootstrap#start()}. For example:
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
    private static final Logger LOG = LoggerFactory.getLogger(DefaultHttpProxyServer.class);

    /**
     * The interval in ms at which the GlobalTrafficShapingHandler will run to compute and throttle the
     * proxy-to-server bandwidth.
     */
    private static final long TRAFFIC_SHAPING_CHECK_INTERVAL_MS = 250L;

    /**
     * The proxy alias to use in the Via header if no explicit proxy alias is specified and the hostname of the local
     * machine cannot be resolved.
     */
    private static final String FALLBACK_PROXY_ALIAS = "littleproxy";

    /**
     * Our {@link ServerGroup}. Multiple proxy servers can share the same
     * ServerGroup in order to reuse threads and other such resources.
     */
    private final ServerGroup serverGroup;

    private final TransportProtocol transportProtocol;
    /*
    * The address that the server will attempt to bind to.
    */
    private final InetSocketAddress requestedAddress;
    /*
    * The actual address to which the server is bound. May be different from the requestedAddress in some circumstances,
    * for example when the requested port is 0.
    */
    private volatile InetSocketAddress localAddress;
    private volatile InetSocketAddress boundAddress;
    private final SslEngineSource sslEngineSource;
    private final boolean authenticateSslClients;
    private final ProxyAuthenticator proxyAuthenticator;
    private final ChainedProxyManager chainProxyManager;
    private final MitmManager mitmManager;
    private final HttpFiltersSource filtersSource;
    private final boolean transparent;
    private volatile int connectTimeout;
    private volatile int idleConnectionTimeout;
    private final HostResolver serverResolver;
    private volatile GlobalTrafficShapingHandler globalTrafficShapingHandler;

    /**
     * The alias or pseudonym for this proxy, used when adding the Via header.
     */
    private final String proxyAlias;

    /**
     * True when the proxy has already been stopped by calling {@link #stop()} or {@link #abort()}.
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Track all ActivityTrackers for tracking proxying activity.
     */
    private final Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<ActivityTracker>();

    /**
     * Keep track of all channels created by this proxy server for later shutdown when the proxy is stopped.
     */
    private final ChannelGroup allChannels = new DefaultChannelGroup("HTTP-Proxy-Server", GlobalEventExecutor.INSTANCE);

    /**
     * JVM shutdown hook to shutdown this proxy server. Declared as a class-level variable to allow removing the shutdown hook when the
     * proxy server is stopped normally.
     */
    private final Thread jvmShutdownHook = new Thread(new Runnable() {
        @Override
        public void run() {
            abort();
        }
    }, "LittleProxy-JVM-shutdown-hook");

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
     * Creates a new proxy server.
     *
     * @param serverGroup
     *            our ServerGroup for shared thread pools and such
     * @param transportProtocol
     *            The protocol to use for data transport
     * @param requestedAddress
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
     * @param transparent
     *            If true, this proxy will run as a transparent proxy. This will
     *            not modify the response, and will only modify the request to
     *            amend the URI if the target is the origin server (to comply
     *            with RFC 7230 section 5.3.1).
     * @param idleConnectionTimeout
     *            The timeout (in seconds) for auto-closing idle connections.
     * @param activityTrackers
     *            for tracking activity on this proxy
     * @param connectTimeout
     *            number of milliseconds to wait to connect to the upstream
     *            server
     * @param serverResolver
     *            the {@link HostResolver} to use for resolving server addresses
     * @param readThrottleBytesPerSecond
     *            read throttle bandwidth
     * @param writeThrottleBytesPerSecond
     *            write throttle bandwidth
     */
    private DefaultHttpProxyServer(ServerGroup serverGroup,
            TransportProtocol transportProtocol,
            InetSocketAddress requestedAddress,
            SslEngineSource sslEngineSource,
            boolean authenticateSslClients,
            ProxyAuthenticator proxyAuthenticator,
            ChainedProxyManager chainProxyManager,
            MitmManager mitmManager,
            HttpFiltersSource filtersSource,
            boolean transparent,
            int idleConnectionTimeout,
            Collection<ActivityTracker> activityTrackers,
            int connectTimeout,
            HostResolver serverResolver,
            long readThrottleBytesPerSecond,
            long writeThrottleBytesPerSecond,
            InetSocketAddress localAddress,
            String proxyAlias) {
        this.serverGroup = serverGroup;
        this.transportProtocol = transportProtocol;
        this.requestedAddress = requestedAddress;
        this.sslEngineSource = sslEngineSource;
        this.authenticateSslClients = authenticateSslClients;
        this.proxyAuthenticator = proxyAuthenticator;
        this.chainProxyManager = chainProxyManager;
        this.mitmManager = mitmManager;
        this.filtersSource = filtersSource;
        this.transparent = transparent;
        this.idleConnectionTimeout = idleConnectionTimeout;
        if (activityTrackers != null) {
            this.activityTrackers.addAll(activityTrackers);
        }
        this.connectTimeout = connectTimeout;
        this.serverResolver = serverResolver;

        if (writeThrottleBytesPerSecond > 0 || readThrottleBytesPerSecond > 0) {
            this.globalTrafficShapingHandler = createGlobalTrafficShapingHandler(transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
        } else {
            this.globalTrafficShapingHandler = null;
        }
        this.localAddress = localAddress;

        if (proxyAlias == null) {
            // attempt to resolve the name of the local machine. if it cannot be resolved, use the fallback name.
            String hostname = ProxyUtils.getHostName();
            if (hostname == null) {
                hostname = FALLBACK_PROXY_ALIAS;
            }
            this.proxyAlias = hostname;
        } else {
            this.proxyAlias = proxyAlias;
        }
    }

    /**
     * Creates a new GlobalTrafficShapingHandler for this HttpProxyServer, using this proxy's proxyToServerEventLoop.
     *
     * @param transportProtocol
     * @param readThrottleBytesPerSecond
     * @param writeThrottleBytesPerSecond
     *
     * @return
     */
    private GlobalTrafficShapingHandler createGlobalTrafficShapingHandler(TransportProtocol transportProtocol, long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
        EventLoopGroup proxyToServerEventLoop = this.getProxyToServerWorkerFor(transportProtocol);
        return new GlobalTrafficShapingHandler(proxyToServerEventLoop,
                writeThrottleBytesPerSecond,
                readThrottleBytesPerSecond,
                TRAFFIC_SHAPING_CHECK_INTERVAL_MS,
                Long.MAX_VALUE);
    }

    boolean isTransparent() {
        return transparent;
    }

    @Override
    public int getIdleConnectionTimeout() {
        return idleConnectionTimeout;
    }

    @Override
    public void setIdleConnectionTimeout(int idleConnectionTimeout) {
        this.idleConnectionTimeout = idleConnectionTimeout;
    }

    @Override
    public int getConnectTimeout() {
        return connectTimeout;
    }

    @Override
    public void setConnectTimeout(int connectTimeoutMs) {
        this.connectTimeout = connectTimeoutMs;
    }

    public HostResolver getServerResolver() {
        return serverResolver;
    }

    public InetSocketAddress getLocalAddress() {
        return localAddress;
    }

    @Override
    public InetSocketAddress getListenAddress() {
        return boundAddress;
    }

    @Override
    public void setThrottle(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
        if (globalTrafficShapingHandler != null) {
            globalTrafficShapingHandler.configure(writeThrottleBytesPerSecond, readThrottleBytesPerSecond);
        } else {
            // don't create a GlobalTrafficShapingHandler if throttling was not enabled and is still not enabled
            if (readThrottleBytesPerSecond > 0 || writeThrottleBytesPerSecond > 0) {
                globalTrafficShapingHandler = createGlobalTrafficShapingHandler(transportProtocol, readThrottleBytesPerSecond, writeThrottleBytesPerSecond);
            }
        }
    }

    public long getReadThrottle() {
        return globalTrafficShapingHandler.getReadLimit();
    }

    public long getWriteThrottle() {
        return globalTrafficShapingHandler.getWriteLimit();
    }

    @Override
    public HttpProxyServerBootstrap clone() {
        return new DefaultHttpProxyServerBootstrap(serverGroup,
                transportProtocol,
                new InetSocketAddress(requestedAddress.getAddress(),
                        requestedAddress.getPort() == 0 ? 0 : requestedAddress.getPort() + 1),
                    sslEngineSource,
                    authenticateSslClients,
                    proxyAuthenticator,
                    chainProxyManager,
                    mitmManager,
                    filtersSource,
                    transparent,
                    idleConnectionTimeout,
                    activityTrackers,
                    connectTimeout,
                    serverResolver,
                    globalTrafficShapingHandler != null ? globalTrafficShapingHandler.getReadLimit() : 0,
                    globalTrafficShapingHandler != null ? globalTrafficShapingHandler.getWriteLimit() : 0,
                    localAddress,
                    proxyAlias);
    }

    @Override
    public void stop() {
        doStop(true);
    }

    @Override
    public void abort() {
        doStop(false);
    }

    /**
     * Performs cleanup necessary to stop the server. Closes all channels opened by the server and unregisters this
     * server from the server group.
     *
     * @param graceful when true, waits for requests to terminate before stopping the server
     */
    protected void doStop(boolean graceful) {
        // only stop the server if it hasn't already been stopped
        if (stopped.compareAndSet(false, true)) {
            if (graceful) {
                LOG.info("Shutting down proxy server gracefully");
            } else {
                LOG.info("Shutting down proxy server immediately (non-graceful)");
            }

            closeAllChannels(graceful);

            serverGroup.unregisterProxyServer(this, graceful);

            // remove the shutdown hook that was added when the proxy was started, since it has now been stopped
            try {
                Runtime.getRuntime().removeShutdownHook(jvmShutdownHook);
            } catch (IllegalStateException e) {
                // ignore -- IllegalStateException means the VM is already shutting down
            }

            LOG.info("Done shutting down proxy server");
        }
    }

    /**
     * Register a new {@link Channel} with this server, for later closing.
     *
     * @param channel
     */
    protected void registerChannel(Channel channel) {
        allChannels.add(channel);
    }

    /**
     * Closes all channels opened by this proxy server.
     *
     * @param graceful when false, attempts to shutdown all channels immediately and ignores any channel-closing exceptions
     */
    protected void closeAllChannels(boolean graceful) {
        LOG.info("Closing all channels " + (graceful ? "(graceful)" : "(non-graceful)"));

        ChannelGroupFuture future = allChannels.close();

        // if this is a graceful shutdown, log any channel closing failures. if this isn't a graceful shutdown, ignore them.
        if (graceful) {
            try {
                future.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();

                LOG.warn("Interrupted while waiting for channels to shut down gracefully.");
            }

            if (!future.isSuccess()) {
                for (ChannelFuture cf : future) {
                    if (!cf.isSuccess()) {
                        LOG.info("Unable to close channel.  Cause of failure for {} is {}", cf.channel(), cf.cause());
                    }
                }
            }
        }
    }

    private HttpProxyServer start() {
        if (!serverGroup.isStopped()) {
            LOG.info("Starting proxy at address: " + this.requestedAddress);

            serverGroup.registerProxyServer(this);

            doStart();
        } else {
            throw new IllegalStateException("Attempted to start proxy, but proxy's server group is already stopped");
        }

        return this;
    }

    private void doStart() {
        ServerBootstrap serverBootstrap = new ServerBootstrap().group(
                serverGroup.getClientToProxyAcceptorPoolForTransport(transportProtocol),
                serverGroup.getClientToProxyWorkerPoolForTransport(transportProtocol));

        ChannelInitializer<Channel> initializer = new ChannelInitializer<Channel>() {
            protected void initChannel(Channel ch) throws Exception {
                new ClientToProxyConnection(
                        DefaultHttpProxyServer.this,
                        sslEngineSource,
                        authenticateSslClients,
                        ch.pipeline(),
                        globalTrafficShapingHandler);
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
                throw new UnknownTransportProtocolException(transportProtocol);
        }
        serverBootstrap.childHandler(initializer);
        ChannelFuture future = serverBootstrap.bind(requestedAddress)
                .addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future)
                            throws Exception {
                        if (future.isSuccess()) {
                            registerChannel(future.channel());
                        }
                    }
                }).awaitUninterruptibly();

        Throwable cause = future.cause();
        if (cause != null) {
            throw new RuntimeException(cause);
        }

        this.boundAddress = ((InetSocketAddress) future.channel().localAddress());
        LOG.info("Proxy started at address: " + this.boundAddress);

        Runtime.getRuntime().addShutdownHook(jvmShutdownHook);
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

    public String getProxyAlias() {
        return proxyAlias;
    }


    protected EventLoopGroup getProxyToServerWorkerFor(TransportProtocol transportProtocol) {
        return serverGroup.getProxyToServerWorkerPoolForTransport(transportProtocol);
    }

    // TODO: refactor bootstrap into a separate class
    private static class DefaultHttpProxyServerBootstrap implements HttpProxyServerBootstrap {
        private String name = "LittleProxy";
        private ServerGroup serverGroup = null;
        private TransportProtocol transportProtocol = TransportProtocol.TCP;
        private InetSocketAddress requestedAddress;
        private int port = 8080;
        private boolean allowLocalOnly = true;
        private SslEngineSource sslEngineSource = null;
        private boolean authenticateSslClients = true;
        private ProxyAuthenticator proxyAuthenticator = null;
        private ChainedProxyManager chainProxyManager = null;
        private MitmManager mitmManager = null;
        private HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter();
        private boolean transparent = false;
        private int idleConnectionTimeout = 70;
        private Collection<ActivityTracker> activityTrackers = new ConcurrentLinkedQueue<ActivityTracker>();
        private int connectTimeout = 40000;
        private HostResolver serverResolver = new DefaultHostResolver();
        private long readThrottleBytesPerSecond;
        private long writeThrottleBytesPerSecond;
        private InetSocketAddress localAddress;
        private String proxyAlias;
        private int clientToProxyAcceptorThreads = ServerGroup.DEFAULT_INCOMING_ACCEPTOR_THREADS;
        private int clientToProxyWorkerThreads = ServerGroup.DEFAULT_INCOMING_WORKER_THREADS;
        private int proxyToServerWorkerThreads = ServerGroup.DEFAULT_OUTGOING_WORKER_THREADS;

        private DefaultHttpProxyServerBootstrap() {
        }

        private DefaultHttpProxyServerBootstrap(
                ServerGroup serverGroup,
                TransportProtocol transportProtocol,
                InetSocketAddress requestedAddress,
                SslEngineSource sslEngineSource,
                boolean authenticateSslClients,
                ProxyAuthenticator proxyAuthenticator,
                ChainedProxyManager chainProxyManager,
                MitmManager mitmManager,
                HttpFiltersSource filtersSource,
                boolean transparent, int idleConnectionTimeout,
                Collection<ActivityTracker> activityTrackers,
                int connectTimeout, HostResolver serverResolver,
                long readThrottleBytesPerSecond,
                long  writeThrottleBytesPerSecond,
                InetSocketAddress localAddress,
                String proxyAlias) {
            this.serverGroup = serverGroup;
            this.transportProtocol = transportProtocol;
            this.requestedAddress = requestedAddress;
            this.port = requestedAddress.getPort();
            this.sslEngineSource = sslEngineSource;
            this.authenticateSslClients = authenticateSslClients;
            this.proxyAuthenticator = proxyAuthenticator;
            this.chainProxyManager = chainProxyManager;
            this.mitmManager = mitmManager;
            this.filtersSource = filtersSource;
            this.transparent = transparent;
            this.idleConnectionTimeout = idleConnectionTimeout;
            if (activityTrackers != null) {
                this.activityTrackers.addAll(activityTrackers);
            }
            this.connectTimeout = connectTimeout;
            this.serverResolver = serverResolver;
            this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
            this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
            this.localAddress = localAddress;
            this.proxyAlias = proxyAlias;
        }

        private DefaultHttpProxyServerBootstrap(Properties props) {
            this.withUseDnsSec(ProxyUtils.extractBooleanDefaultFalse(
                    props, "dnssec"));
            this.transparent = ProxyUtils.extractBooleanDefaultFalse(
                    props, "transparent");
            this.idleConnectionTimeout = ProxyUtils.extractInt(props,
                    "idle_connection_timeout");
            this.connectTimeout = ProxyUtils.extractInt(props,
                    "connect_timeout", 0);
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
            this.requestedAddress = address;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withPort(int port) {
            this.requestedAddress = null;
            this.port = port;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withNetworkInterface(InetSocketAddress inetSocketAddress) {
            this.localAddress = inetSocketAddress;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withProxyAlias(String alias) {
            this.proxyAlias = alias;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withAllowLocalOnly(
                boolean allowLocalOnly) {
            this.allowLocalOnly = allowLocalOnly;
            return this;
        }

        @Override
        @Deprecated
        public HttpProxyServerBootstrap withListenOnAllAddresses(boolean listenOnAllAddresses) {
            LOG.warn("withListenOnAllAddresses() is deprecated and will be removed in a future release. Use withNetworkInterface().");
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withSslEngineSource(
                SslEngineSource sslEngineSource) {
            this.sslEngineSource = sslEngineSource;
            if (this.mitmManager != null) {
                LOG.warn("Enabled encrypted inbound connections with man in the middle. "
                        + "These are mutually exclusive - man in the middle will be disabled.");
                this.mitmManager = null;
            }
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
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withManInTheMiddle(
                MitmManager mitmManager) {
            this.mitmManager = mitmManager;
            if (this.sslEngineSource != null) {
                LOG.warn("Enabled man in the middle with encrypted inbound connections. "
                        + "These are mutually exclusive - encrypted inbound connections will be disabled.");
                this.sslEngineSource = null;
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
            if (useDnsSec) {
                this.serverResolver = new DnsSecServerResolver();
            } else {
                this.serverResolver = new DefaultHostResolver();
            }
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
        public HttpProxyServerBootstrap withConnectTimeout(
                int connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withServerResolver(
                HostResolver serverResolver) {
            this.serverResolver = serverResolver;
            return this;
        }

        @Override
        public HttpProxyServerBootstrap plusActivityTracker(
                ActivityTracker activityTracker) {
            activityTrackers.add(activityTracker);
            return this;
        }

        @Override
        public HttpProxyServerBootstrap withThrottling(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond) {
            this.readThrottleBytesPerSecond = readThrottleBytesPerSecond;
            this.writeThrottleBytesPerSecond = writeThrottleBytesPerSecond;
            return this;
        }

        @Override
        public HttpProxyServer start() {
            return build().start();
        }

        @Override
        public HttpProxyServerBootstrap withThreadPoolConfiguration(ThreadPoolConfiguration configuration) {
            this.clientToProxyAcceptorThreads = configuration.getAcceptorThreads();
            this.clientToProxyWorkerThreads = configuration.getClientToProxyWorkerThreads();
            this.proxyToServerWorkerThreads = configuration.getProxyToServerWorkerThreads();
            return this;
        }

        private DefaultHttpProxyServer build() {
            final ServerGroup serverGroup;

            if (this.serverGroup != null) {
                serverGroup = this.serverGroup;
            }
            else {
                serverGroup = new ServerGroup(name, clientToProxyAcceptorThreads, clientToProxyWorkerThreads, proxyToServerWorkerThreads);
            }

            return new DefaultHttpProxyServer(serverGroup,
                    transportProtocol, determineListenAddress(),
                    sslEngineSource, authenticateSslClients,
                    proxyAuthenticator, chainProxyManager, mitmManager,
                    filtersSource, transparent,
                    idleConnectionTimeout, activityTrackers, connectTimeout,
                    serverResolver, readThrottleBytesPerSecond, writeThrottleBytesPerSecond,
                    localAddress, proxyAlias);
        }

        private InetSocketAddress determineListenAddress() {
            if (requestedAddress != null) {
                return requestedAddress;
            } else {
                // Binding only to localhost can significantly improve the
                // security of the proxy.
                if (allowLocalOnly) {
                    return new InetSocketAddress("127.0.0.1", port);
                } else {
                    return new InetSocketAddress(port);
                }
            }
        }
    }
}
