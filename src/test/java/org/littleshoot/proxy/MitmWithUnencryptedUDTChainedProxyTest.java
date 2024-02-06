package org.littleshoot.proxy;

import org.junit.BeforeClass;

import static org.littleshoot.proxy.TestUtils.disableOnMac;
import static org.littleshoot.proxy.TransportProtocol.UDT;

public class MitmWithUnencryptedUDTChainedProxyTest extends MitmWithChainedProxyTest {
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
        };
    }
}
