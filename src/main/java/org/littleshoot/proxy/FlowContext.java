package org.littleshoot.proxy;

import java.net.InetSocketAddress;

import org.littleshoot.proxy.impl.ClientToProxyConnection;

/**
 * <p>
 * Encapsulates contextual information for flow information that's being
 * reported to a {@link ActivityTracker}.
 * </p>
 */
public class FlowContext {
    private final InetSocketAddress clientAddress;

    public FlowContext(ClientToProxyConnection clientConnection) {
        super();
        this.clientAddress = clientConnection.getClientAddress();
    }

    /**
     * The address of the client.
     * 
     * @return
     */
    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

}
