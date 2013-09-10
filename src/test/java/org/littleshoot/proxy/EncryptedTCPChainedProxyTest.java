package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;

import javax.net.ssl.SSLEngine;

public class EncryptedTCPChainedProxyTest extends BaseChainedProxyTest {
    private final SslEngineSource sslEngineSource = new SelfSignedSSLEngineSource(
            "chain_proxy_keystore_1.jks");

    @Override
    protected HttpProxyServerBootstrap downstreamProxy() {
        return super.downstreamProxy()
                .withTransportProtocol(TCP)
                .withSslEngineSource(sslEngineSource);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
            @Override
            public TransportProtocol getTransportProtocol() {
                return TransportProtocol.TCP;
            }

            @Override
            public boolean requiresEncryption() {
                return true;
            }

            @Override
            public SSLEngine newSslEngine() {
                return sslEngineSource.newSslEngine();
            }
        };
    }
}
