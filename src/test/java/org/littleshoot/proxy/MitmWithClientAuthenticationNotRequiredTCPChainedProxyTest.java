package org.littleshoot.proxy;

import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

import javax.net.ssl.SSLEngine;

import static org.littleshoot.proxy.TransportProtocol.TCP;

/**
 * Tests that when client authentication is not required, it doesn't matter what
 * certs the client sends.
 */
public class MitmWithClientAuthenticationNotRequiredTCPChainedProxyTest extends
        MitmWithChainedProxyTest {
    private final SslEngineSource serverSslEngineSource = new SelfSignedSslEngineSource(
            "chain_proxy_keystore_1.jks");

    private final SslEngineSource clientSslEngineSource = new SelfSignedSslEngineSource(
            "chain_proxy_keystore_1.jks", false, false);

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(TCP)
                .withSslEngineSource(serverSslEngineSource)
                .withAuthenticateSslClients(false);
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
                return clientSslEngineSource.newSslEngine();
            }
        };
    }
}
