package org.littleshoot.proxy.impl;

import java.net.InetSocketAddress;

/**
 * Contains information about the client.
 */
public class ClientDetails {

    /**
     * The user name that was used for authentication, or null if authentication wasn't performed.
     */
    private volatile String userName;

    /**
     * The client's address
     */
    private volatile InetSocketAddress clientAddress;

    public String getUserName() {
        return userName;
    }

    void setUserName(String userName) {
        this.userName = userName;
    }

    public InetSocketAddress getClientAddress() {
        return clientAddress;
    }

    void setClientAddress(InetSocketAddress clientAddress) {
        this.clientAddress = clientAddress;
    }
}
