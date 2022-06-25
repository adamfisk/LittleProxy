package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.ClientToProxyConnection;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;

/**
 * <p>
 * Encapsulates contextual information for flow information that's being
 * reported to a {@link ActivityTracker}.
 * </p>
 */
public class FlowContext {
    private final InetSocketAddress clientAddress;
    private final SSLSession clientSslSession;

    public FlowContext(ClientToProxyConnection clientConnection) {
        super();
        this.clientAddress = clientConnection.getClientAddress();
        SSLEngine sslEngine = clientConnection.getSslEngine();
        this.clientSslSession = sslEngine != null ? sslEngine.getSession()
                : null;
    }

    /**
     * The address of the client.
     */
    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    /**
     * If using SSL, this returns the {@link SSLSession} on the client
     * connection.
     */
    public SSLSession getClientSslSession() {
        return clientSslSession;
    }

}
