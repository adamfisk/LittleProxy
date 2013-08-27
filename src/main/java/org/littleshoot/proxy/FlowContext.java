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
    private final String chainedProxyHostAndPort;

    public FlowContext(
            InetSocketAddress clientAddress,
            TransportProtocol outboundTransportProtocol,
            String serverHostAndPort,
            String chainedProxyHostAndPort) {
        super();
        this.outboundTransportProtocol = outboundTransportProtocol;
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

    /**
     * The address of the client.
     * 
     * @return
     */
    public InetSocketAddress getClientAddress() {
        return clientAddress;
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
     * The transport protocol going out of the proxy (either to the server or a
     * chained proxy).
     * 
     * @return
     */
    public TransportProtocol getOutboundTransportProtocol() {
        return outboundTransportProtocol;
    }

    /**
     * The host and port for the chained proxy (if chaining).
     * 
     * @return
     */
    public String getChainedProxyHostAndPort() {
        return chainedProxyHostAndPort;
    }

}
