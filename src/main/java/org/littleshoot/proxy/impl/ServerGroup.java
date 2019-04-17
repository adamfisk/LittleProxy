package org.littleshoot.proxy.impl;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.udt.nio.NioUdtProvider;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.TransportProtocol;
import org.littleshoot.proxy.UnknownTransportProtocolException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.channels.spi.SelectorProvider;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages thread pools for one or more proxy server instances. When servers are created, they must register with the
 * ServerGroup using {@link #registerProxyServer(HttpProxyServer)}, and when they shut down, must unregister with the
 * ServerGroup using {@link #unregisterProxyServer(HttpProxyServer, boolean)}.
 */
public class ServerGroup {
    private static final Logger log = LoggerFactory.getLogger(ServerGroup.class);

    /**
     * The default number of threads to accept incoming requests from clients. (Requests are serviced by worker threads,
     * not acceptor threads.)
     */
    public static final int DEFAULT_INCOMING_ACCEPTOR_THREADS = 2;

    /**
     * The default number of threads to service incoming requests from clients.
     */
    public static final int DEFAULT_INCOMING_WORKER_THREADS = 8;

    /**
     * The default number of threads to service outgoing requests to servers.
     */
    public static final int DEFAULT_OUTGOING_WORKER_THREADS = 8;

    /**
     * Global counter for the {@link #serverGroupId}.
     */
    private static final AtomicInteger serverGroupCount = new AtomicInteger(0);

    /**
     * A name for this ServerGroup to use in naming threads.
     */
    private final String name;

    /**
     * The ID of this server group. Forms part of the name of each thread created for this server group. Useful for
     * differentiating threads when multiple proxy instances are running.
      */
    private final int serverGroupId;

    private final int incomingAcceptorThreads;
    private final int incomingWorkerThreads;
    private final int outgoingWorkerThreads;

    /**
     * List of all servers registered to use this ServerGroup. Any access to this list should be synchronized using the
     * {@link #SERVER_REGISTRATION_LOCK}.
     */
    public final List<HttpProxyServer> registeredServers = new ArrayList<>(1);

    /**
     * A mapping of {@link TransportProtocol}s to their initialized {@link ProxyThreadPools}. Each transport uses a
     * different thread pool, since the initialization parameters are different.
     */
    private final EnumMap<TransportProtocol, ProxyThreadPools> protocolThreadPools = new EnumMap<>(TransportProtocol.class);

    /**
     * A mapping of selector providers to transport protocols. Avoids special-casing each transport protocol during
     * transport protocol initialization.
     */
    private static final EnumMap<TransportProtocol, SelectorProvider> TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS = new EnumMap<>(TransportProtocol.class);
    static {
        TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.put(TransportProtocol.TCP, SelectorProvider.provider());

        // allow the proxy to operate without UDT support. this allows clients that do not use UDT to exclude the barchart
        // dependency completely.
        if (ProxyUtils.isUdtAvailable()) {
            TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.put(TransportProtocol.UDT, NioUdtProvider.BYTE_PROVIDER);
        } else {
            log.debug("UDT provider not found on classpath. UDT transport will not be available.");
        }
    }

    /**
     * True when this ServerGroup is stopped.
     */
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    /**
     * Creates a new ServerGroup instance for a proxy. Threads created for this ServerGroup will have the specified
     * ServerGroup name in the Thread name. This constructor does not actually initialize any thread pools; instead,
     * thread pools for specific transport protocols are lazily initialized as needed.
     *
     * @param name ServerGroup name to include in thread names
     * @param incomingAcceptorThreads number of acceptor threads per protocol
     * @param incomingWorkerThreads number of client-to-proxy worker threads per protocol
     * @param outgoingWorkerThreads number of proxy-to-server worker threads per protocol
     */
    public ServerGroup(String name, int incomingAcceptorThreads, int incomingWorkerThreads, int outgoingWorkerThreads) {
        this.name = name;
        this.serverGroupId = serverGroupCount.getAndIncrement();
        this.incomingAcceptorThreads = incomingAcceptorThreads;
        this.incomingWorkerThreads = incomingWorkerThreads;
        this.outgoingWorkerThreads = outgoingWorkerThreads;
    }

    /**
     * Lock for initializing any transport protocols.
     */
    private final Object THREAD_POOL_INIT_LOCK = new Object();

    /**
     * Retrieves the {@link ProxyThreadPools} for the specified transport protocol. Lazily initializes the thread pools
     * for the transport protocol if they have not yet been initialized. If the protocol has already been initialized,
     * this method returns immediately, without synchronization. If initialization is necessary, the initialization
     * process creates the acceptor and worker threads necessary to service requests to/from the proxy.
     * <p>
     * This method is thread-safe; no external locking is necessary.
     *
     * @param protocol transport protocol to retrieve thread pools for
     * @return thread pools for the specified transport protocol
     */
    private ProxyThreadPools getThreadPoolsForProtocol(TransportProtocol protocol) {
        // if the thread pools have not been initialized for this protocol, initialize them
        if (protocolThreadPools.get(protocol) == null) {
            synchronized (THREAD_POOL_INIT_LOCK) {
                if (protocolThreadPools.get(protocol) == null) {
                    log.debug("Initializing thread pools for {} with {} acceptor threads, {} incoming worker threads, and {} outgoing worker threads",
                            protocol, incomingAcceptorThreads, incomingWorkerThreads, outgoingWorkerThreads);

                    SelectorProvider selectorProvider = TRANSPORT_PROTOCOL_SELECTOR_PROVIDERS.get(protocol);
                    if (selectorProvider == null) {
                        throw new UnknownTransportProtocolException(protocol);
                    }

                    ProxyThreadPools threadPools = new ProxyThreadPools(selectorProvider,
                            incomingAcceptorThreads,
                            incomingWorkerThreads,
                            outgoingWorkerThreads,
                            name,
                            serverGroupId);
                    protocolThreadPools.put(protocol, threadPools);
                }
            }
        }

        return protocolThreadPools.get(protocol);
    }

    /**
     * Lock controlling access to the {@link #registerProxyServer(HttpProxyServer)} and {@link #unregisterProxyServer(HttpProxyServer, boolean)}
     * methods.
     */
    private final Object SERVER_REGISTRATION_LOCK = new Object();

    /**
     * Registers the specified proxy server as a consumer of this server group. The server group will not be shut down
     * until the proxy unregisters itself.
     *
     * @param proxyServer proxy server instance to register
     */
    public void registerProxyServer(HttpProxyServer proxyServer) {
        synchronized (SERVER_REGISTRATION_LOCK) {
            registeredServers.add(proxyServer);
        }
    }

    /**
     * Unregisters the specified proxy server from this server group. If this was the last registered proxy server, the
     * server group will be shut down.
     *
     * @param proxyServer proxy server instance to unregister
     * @param graceful when true, the server group shutdown (if necessary) will be graceful
     */
    public void unregisterProxyServer(HttpProxyServer proxyServer, boolean graceful) {
        synchronized (SERVER_REGISTRATION_LOCK) {
            boolean wasRegistered = registeredServers.remove(proxyServer);
            if (!wasRegistered) {
                log.warn("Attempted to unregister proxy server from ServerGroup that it was not registered with. Was the proxy unregistered twice?");
            }

            if (registeredServers.isEmpty()) {
                log.debug("Proxy server unregistered from ServerGroup. No proxy servers remain registered, so shutting down ServerGroup.");

                shutdown(graceful);
            } else {
                log.debug("Proxy server unregistered from ServerGroup. Not shutting down ServerGroup ({} proxy servers remain registered).", registeredServers.size());
            }
        }
    }

    /**
     * Shuts down all event loops owned by this server group.
     *
     * @param graceful when true, event loops will "gracefully" terminate, waiting for submitted tasks to finish
     */
    private void shutdown(boolean graceful) {
        if (!stopped.compareAndSet(false, true)) {
            log.info("Shutdown requested, but ServerGroup is already stopped. Doing nothing.");

            return;
        }

        log.info("Shutting down server group event loops " + (graceful ? "(graceful)" : "(non-graceful)"));

        // loop through all event loops managed by this server group. this includes acceptor and worker event loops
        // for both TCP and UDP transport protocols.
        List<EventLoopGroup> allEventLoopGroups = new ArrayList<>();

        for (ProxyThreadPools threadPools : protocolThreadPools.values()) {
            allEventLoopGroups.addAll(threadPools.getAllEventLoops());
        }

        for (EventLoopGroup group : allEventLoopGroups) {
            if (graceful) {
                group.shutdownGracefully();
            } else {
                group.shutdownGracefully(0, 0, TimeUnit.SECONDS);
            }
        }

        if (graceful) {
            for (EventLoopGroup group : allEventLoopGroups) {
                try {
                    group.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();

                    log.warn("Interrupted while shutting down event loop");
                }
            }
        }

        log.debug("Done shutting down server group");
    }

    /**
     * Retrieves the client-to-proxy acceptor thread pool for the specified protocol. Initializes the pool if it has not
     * yet been initialized.
     * <p>
     * This method is thread-safe; no external locking is necessary.
     *
     * @param protocol transport protocol to retrieve the thread pool for
     * @return the client-to-proxy acceptor thread pool
     */
    public EventLoopGroup getClientToProxyAcceptorPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getClientToProxyAcceptorPool();
    }

    /**
     * Retrieves the client-to-proxy acceptor worker pool for the specified protocol. Initializes the pool if it has not
     * yet been initialized.
     * <p>
     * This method is thread-safe; no external locking is necessary.
     *
     * @param protocol transport protocol to retrieve the thread pool for
     * @return the client-to-proxy worker thread pool
     */
    public EventLoopGroup getClientToProxyWorkerPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getClientToProxyWorkerPool();
    }

    /**
     * Retrieves the proxy-to-server worker thread pool for the specified protocol. Initializes the pool if it has not
     * yet been initialized.
     * <p>
     * This method is thread-safe; no external locking is necessary.
     *
     * @param protocol transport protocol to retrieve the thread pool for
     * @return the proxy-to-server worker thread pool
     */
    public EventLoopGroup getProxyToServerWorkerPoolForTransport(TransportProtocol protocol) {
        return getThreadPoolsForProtocol(protocol).getProxyToServerWorkerPool();
    }

    /**
     * @return true if this ServerGroup has already been stopped
     */
    public boolean isStopped() {
        return stopped.get();
    }

}
