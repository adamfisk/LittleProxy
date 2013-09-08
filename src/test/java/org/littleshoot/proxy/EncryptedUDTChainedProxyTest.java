package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;

import javax.net.ssl.SSLEngine;

public class EncryptedUDTChainedProxyTest extends BaseChainedProxyTest {
    private final SSLEngineSource sslEngineSource = new SelfSignedSSLEngineSource(
            "chain_proxy_keystore_1.jks");

    @Override
    protected HttpProxyServerBootstrap downstreamProxy() {
        return super.downstreamProxy()
                .withTransportProtocol(UDT)
                .withSSLEngineSource(sslEngineSource);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
            @Override
            public TransportProtocol getTransportProtocol() {
                return TransportProtocol.UDT;
            }

            @Override
            public boolean requiresEncryption() {
                return true;
            }

            @Override
            public SSLEngine newSSLEngine() {
                return sslEngineSource.newSSLEngine();
            }
        };
    }
}
