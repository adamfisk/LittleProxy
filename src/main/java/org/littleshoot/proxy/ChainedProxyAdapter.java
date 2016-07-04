package org.littleshoot.proxy;

import org.littleshoot.proxy.impl.ConnectionFlowStep;
import org.littleshoot.proxy.impl.ProxyConnection;

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
    public boolean requiresCustomConnectionFlow() {
        return false;
    }

    @Override
    public ConnectionFlowStep customConnectionFlow(ProxyConnection connection) {
        return null;
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
