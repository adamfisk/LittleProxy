package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import javax.net.ssl.SSLContext;

public class ChainedProxyManagerAdapter implements ChainedProxyManager {

    @Override
    public String getHostAndPort(HttpRequest httpRequest) {
        return null;
    }

    @Override
    public boolean requiresTLSEncryption(HttpRequest httpRequest) {
        return false;
    }

    @Override
    public SSLContext getSSLContext() {
        return null;
    }

    @Override
    public TransportProtocol getTransportProtocol() {
        return TransportProtocol.TCP;
    }

}
