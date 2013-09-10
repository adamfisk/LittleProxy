package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;

public class UnencryptedTCPChainedProxyTest extends BaseChainedProxyTest {
    @Override
    protected HttpProxyServerBootstrap downstreamProxy() {
        return super.downstreamProxy()
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
