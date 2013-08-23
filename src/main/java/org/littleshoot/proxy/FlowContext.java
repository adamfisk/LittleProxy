package org.littleshoot.proxy;

import java.net.InetSocketAddress;

/**
 * Encapsulates contextual information for flow information that's being
 * reported to a {@link ActivityTracker}.
 */
public class FlowContext {
    private InetSocketAddress clientAddress;
    private String serverHostAndPort;
    private String chainedProxyHostAndPort;

    public FlowContext(InetSocketAddress clientAddress,
            String serverHostAndPort, String chainedProxyHostAndPort) {
        super();
        this.clientAddress = clientAddress;
        this.serverHostAndPort = serverHostAndPort;
        this.chainedProxyHostAndPort = chainedProxyHostAndPort;
    }

    public FlowContext(ClientToProxyConnection clientConnection,
            ProxyToServerConnection serverConnection) {
        this(clientConnection.getClientAddress(), serverConnection
                .getServerHostAndPort(), serverConnection
                .getChainedProxyHostAndPort());
    }

    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    public String getServerHostAndPort() {
        return serverHostAndPort;
    }

    public String getChainedProxyHostAndPort() {
        return chainedProxyHostAndPort;
    }

}
