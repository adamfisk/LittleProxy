package org.littleshoot.proxy.impl;

import com.google.common.collect.ImmutableList;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import java.nio.channels.spi.SelectorProvider;
import java.util.List;

/**
 * Encapsulates the thread pools used by the proxy. Contains the acceptor thread pool as well as the client-to-proxy and
 * proxy-to-server thread pools.
 */
public class ProxyThreadPools {
    /**
     * These {@link EventLoopGroup}s accept incoming connections to the
     * proxies. A different EventLoopGroup is used for each
     * TransportProtocol, since these have to be configured differently.
     */
    private final EpollEventLoopGroup clientToProxyAcceptorPool;

    /**
     * These {@link EventLoopGroup}s process incoming requests to the
     * proxies. A different EventLoopGroup is used for each
     * TransportProtocol, since these have to be configured differently.
     */
    private final EpollEventLoopGroup clientToProxyWorkerPool;

    /**
     * These {@link EventLoopGroup}s are used for making outgoing
     * connections to servers. A different EventLoopGroup is used for each
     * TransportProtocol, since these have to be configured differently.
     */
    private final EpollEventLoopGroup proxyToServerWorkerPool;

    public ProxyThreadPools(SelectorProvider selectorProvider, int incomingAcceptorThreads, int incomingWorkerThreads, int outgoingWorkerThreads, String serverGroupName, int serverGroupId) {
        clientToProxyAcceptorPool = new EpollEventLoopGroup(incomingAcceptorThreads, new CategorizedThreadFactory(serverGroupName, "ClientToProxyAcceptor", serverGroupId));

        clientToProxyWorkerPool = new EpollEventLoopGroup(incomingWorkerThreads, new CategorizedThreadFactory(serverGroupName, "ClientToProxyWorker", serverGroupId));
        clientToProxyWorkerPool.setIoRatio(90);

        proxyToServerWorkerPool = new EpollEventLoopGroup(outgoingWorkerThreads, new CategorizedThreadFactory(serverGroupName, "ProxyToServerWorker", serverGroupId));
        proxyToServerWorkerPool.setIoRatio(90);
    }

    /**
     * Returns all event loops (acceptor and worker thread pools) in this pool.
     */
    public List<EventLoopGroup> getAllEventLoops() {
        return ImmutableList.<EventLoopGroup>of(clientToProxyAcceptorPool, clientToProxyWorkerPool, proxyToServerWorkerPool);
    }

    public EpollEventLoopGroup getClientToProxyAcceptorPool() {
        return clientToProxyAcceptorPool;
    }

    public EpollEventLoopGroup getClientToProxyWorkerPool() {
        return clientToProxyWorkerPool;
    }

    public EpollEventLoopGroup getProxyToServerWorkerPool() {
        return proxyToServerWorkerPool;
    }
}
