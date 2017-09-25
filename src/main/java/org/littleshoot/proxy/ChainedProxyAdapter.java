package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLEngine;

/**
 * Convenience base class for implementations of {@link ChainedProxy}.
 */
public class ChainedProxyAdapter implements ChainedProxy {
    /**
     * {@link ChainedProxy} that simply has the downstream proxy make a direct
     * connection to the upstream server.
     */
    public static ChainedProxy FALLBACK_TO_DIRECT_CONNECTION = new ChainedProxyAdapter();

    @Override
    public InetSocketAddress getChainedProxyAddress() {
        return null;
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return null;
    }

    @Override
    public TransportProtocol getTransportProtocol() {
        return TransportProtocol.TCP;
    }

    @Override
    public boolean requiresEncryption() {
        return false;
    }

    @Override
    public SSLEngine newSslEngine() {
        return null;
    }
    
    @Override
    public void filterRequest(HttpObject httpObject) {
    }
    
    @Override
    public void connectionSucceeded() {
    }

    @Override
    public void connectionFailed(Throwable cause) {
    }

    @Override
    public void disconnected() {
    }

    @Override
    public SSLEngine newSslEngine(String peerHost, int peerPort) {
        return null;
    }

    @Override
    public String getBasicAuthUser() {
        return null;
    }

    @Override
    public String getBasicAuthPassword() {
        return null;
    }
}
