package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.TCP;

public class MitmWithUnencryptedTCPChainedProxyTest extends MitmWithChainedProxyTest {
    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(TCP);
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
                return false;
            }
        };
    }
}
