package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLEngine;

import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

public class EncryptedUDTChainedProxyTest extends BaseChainedProxyTest {
    private static final AtomicInteger localPort = new AtomicInteger(61000);

    private final SslEngineSource sslEngineSource = new SelfSignedSslEngineSource(
            "chain_proxy_keystore_1.jks");

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(UDT)
                .withSslEngineSource(sslEngineSource);
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
            public SSLEngine newSslEngine() {
                return sslEngineSource.newSslEngine();
            }

            @Override
            public InetSocketAddress getLocalAddress() {
                return new InetSocketAddress("127.0.0.1",
                        localPort.getAndIncrement());
            }
        };
    }
}
