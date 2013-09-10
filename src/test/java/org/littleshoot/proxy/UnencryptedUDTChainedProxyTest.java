package org.littleshoot.proxy;

import static org.littleshoot.proxy.TransportProtocol.*;

public class UnencryptedUDTChainedProxyTest extends BaseChainedProxyTest {
    @Override
    protected HttpProxyServerBootstrap downstreamProxy() {
        return super.downstreamProxy()
                .withTransportProtocol(UDT);
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
                return false;
            }
        };
    }
}
