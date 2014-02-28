package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;

import javax.net.ssl.SSLEngine;

import org.littleshoot.proxy.ntlm.NtlmHandler;

/**
 * Convenience base class for implementations of {@link ChainedProxy}.
 */
public class ChainedProxyAdapter implements ChainedProxy {
    /**
     * {@link ChainedProxy} that simply has the downstream proxy make a direct
     * connection to the upstream server.
     */
    public static ChainedProxy FALLBACK_TO_DIRECT_CONNECTION = new ChainedProxyAdapter();

    private NtlmHandler ntlmHandler;

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
    public boolean requiresNtlmAuthentication() {
        return ntlmHandler != null;
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

    public void setNtlmMessageHandler(NtlmHandler handler) {
        ntlmHandler = handler;
    }

    @Override
    public void writeType1Header(HttpRequest httpRequest) {
        ntlmHandler.writeType1Header(httpRequest);
    }

    @Override
    public void readType2Header(HttpResponse httpResponse) throws Exception {
        ntlmHandler.readType2Header(httpResponse);
    }

    @Override
    public void writeType3Header(HttpRequest httpRequest) {
        ntlmHandler.writeType3Header(httpRequest);
    }

}
