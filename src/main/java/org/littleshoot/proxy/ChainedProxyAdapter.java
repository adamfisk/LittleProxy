package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;

import javax.net.ssl.SSLEngine;
import java.net.InetSocketAddress;

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
    public ChainedProxyType getChainedProxyType() {
        return ChainedProxyType.HTTP;
    }
    
    @Override
    public String getUsername() {
        return null;
    }
    
    @Override
    public String getPassword() {
        return null;
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
}
