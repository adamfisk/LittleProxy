package org.littleshoot.proxy;

import java.net.InetSocketAddress;

import org.littleshoot.proxy.impl.ClientToProxyConnection;
import org.littleshoot.proxy.impl.ProxyToServerConnection;

/**
 * Encapsulates contextual information for flow information that's being
 * reported to a {@link ActivityTracker}.
 */
public class FlowContext {
    private final InetSocketAddress clientAddress;
    private final TransportProtocol transportProtocolToServer;
    private final String serverHostAndPort;
    private final String chainedProxyHostAndPort;

    public FlowContext(
            InetSocketAddress clientAddress,
            TransportProtocol transportProtocolToServer,
            String serverHostAndPort,
            String chainedProxyHostAndPort) {
        super();
        this.transportProtocolToServer = transportProtocolToServer;
        this.clientAddress = clientAddress;
        this.serverHostAndPort = serverHostAndPort;
        this.chainedProxyHostAndPort = chainedProxyHostAndPort;
    }

    public FlowContext(ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection) {
        this(clientConnection.getClientAddress(), serverConnection
                .getTransportProtocol(), serverConnection
                .getServerHostAndPort(), serverConnection
                .getChainedProxyHostAndPort());
    }

    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public TransportProtocol getTransportProtocolToServer() {
        return transportProtocolToServer;
    }

    public String getChainedProxyHostAndPort() {
        return chainedProxyHostAndPort;
    }

}
