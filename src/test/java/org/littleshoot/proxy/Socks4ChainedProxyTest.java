package org.littleshoot.proxy;

public class Socks4ChainedProxyTest extends BaseChainedSocksProxyTest {
    @Override
    protected ChainedProxyType getSocksProxyType() {
        return ChainedProxyType.SOCKS4;
    }
}
