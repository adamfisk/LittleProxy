package org.littleshoot.proxy;

import org.junit.Assert;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Tests a proxy chained to a missing downstream proxy. When the downstream
 * proxy is unavailable, the downstream proxy should just fall back to a direct
 * connection.
 */
public class ChainedProxyWithFallbackTest extends BaseProxyTest {
    private final AtomicBoolean unableToConnect = new AtomicBoolean(false);

    @Override
    protected void setUp() {
        unableToConnect.set(false);
        this.proxyServer = bootstrapProxy()
                .withName("Downstream")
                .withPort(0)
                .withChainProxyManager((httpRequest, chainedProxies, clientDetails) -> {
                    chainedProxies.add(new ChainedProxyAdapter() {
                        @Override
                        public InetSocketAddress getChainedProxyAddress() {
                            try {
                                // using unconnectable port 0
                                return new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0);
                            } catch (UnknownHostException uhe) {
                                throw new RuntimeException(
                                        "Unable to resolve 127.0.0.1?!");
                            }
                        }

                        @Override
                        public void connectionFailed(Throwable cause) {
                            unableToConnect.set(true);
                        }

                    });

                    chainedProxies
                            .add(ChainedProxyAdapter.FALLBACK_TO_DIRECT_CONNECTION);
                })
                .start();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        Assert.assertTrue(
                "We should have been told that we were unable to connect",
                unableToConnect.get());
    }
}
