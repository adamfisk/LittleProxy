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
    private final TransportProtocol outboundTransportProtocol;
    private final String serverHostAndPort;
    private final InetSocketAddress chainedProxyAddress;

    public FlowContext(
            InetSocketAddress clientAddress,
            TransportProtocol outboundTransportProtocol,
            String serverHostAndPort,
            InetSocketAddress chainedProxyAddress) {
        super();
        this.outboundTransportProtocol = outboundTransportProtocol;
        this.clientAddress = clientAddress;
        this.serverHostAndPort = serverHostAndPort;
        this.chainedProxyAddress = chainedProxyAddress;
    }

    public FlowContext(ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection) {
        this(clientConnection.getClientAddress(), serverConnection
                .getTransportProtocol(), serverConnection
                .getServerHostAndPort(), serverConnection
                .getChainedProxyAddress());
    }

    /**
     * The address of the client.
     * 
     * @return
     */
    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    /**
     * The transport protocol going out of the proxy (either to the server or a
     * chained proxy).
     * 
     * @return
     */
    public TransportProtocol getOutboundTransportProtocol() {
        return outboundTransportProtocol;
    }

    /**
     * The host and port for the server (i.e. the ultimate endpoint).
     * 
     * @return
     */
    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    /**
     * The address for the chained proxy (if chaining).
     * 
     * @return
     */
    public InetSocketAddress getChainedProxyAddress() {
        return chainedProxyAddress;
    }

}
