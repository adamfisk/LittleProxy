package org.littleshoot.proxy;

import org.junit.BeforeClass;

import static org.littleshoot.proxy.TestUtils.disableOnMac;
import static org.littleshoot.proxy.TransportProtocol.UDT;

public class UnencryptedUDTChainedProxyTest extends BaseChainedProxyTest {
    @BeforeClass
    public static void beforeClass() {
        disableOnMac();
    }

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withTransportProtocol(UDT);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
            @Override
            public TransportProtocol getTransportProtocol() {
                return UDT;
            }

            @Override
            public boolean requiresEncryption() {
                return false;
            }
        };
    }
}
