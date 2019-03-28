package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.impl.ClientDetails;

import java.util.Queue;

import javax.net.ssl.SSLEngine;

/**
 * Tests a proxy chained to a downstream proxy with an untrusted SSL cert. When
 * the downstream proxy is unavailable, the downstream proxy should just fall
 * back to a the next chained proxy.
 */
public class ChainedProxyWithFallbackToOtherChainedProxyDueToSSLTest extends
        BadServerAuthenticationTCPChainedProxyTest {
    @Override
    protected boolean expectBadGatewayForEverything() {
        return false;
    }

    protected ChainedProxyManager chainedProxyManager() {
        return new ChainedProxyManager() {
            @Override
            public void lookupChainedProxies(HttpRequest httpRequest,
                                             Queue<ChainedProxy> chainedProxies,
                                             ClientDetails clientDetails) {
                // This first one has a bad cert
                chainedProxies.add(newChainedProxy());
                // This 2nd one should work
                chainedProxies.add(new BaseChainedProxy() {
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
                        return serverSslEngineSource.newSslEngine();
                    }
                });
            }
        };
    }
}
