package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.util.Queue;

import org.littleshoot.proxy.extras.SelfSignedMitmManager;

/**
 * Tests just a single basic proxy.
 */
public class MitmProxyTest extends BaseProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(proxyServerPort)
                // Include a ChainedProxyManager to make sure that MITM setting
                // overrides this
                .withChainProxyManager(new ChainedProxyManager() {
                    @Override
                    public void lookupChainedProxies(HttpRequest httpRequest,
                            Queue<ChainedProxy> chainedProxies) {
                    }
                })
                .withManInTheMiddle(new SelfSignedMitmManager())
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public HttpResponse requestPre(HttpObject httpObject) {
                                System.out.println("*********** requestPre: " + httpObject);
                                return null;
                            }

                            @Override
                            public HttpResponse requestPost(
                                    HttpObject httpObject) {
                                System.out.println("*********** requestPost: " + httpObject);
                                return null;
                            }

                            @Override
                            public void responsePre(HttpObject httpObject) {
                                System.out.println("*********** responsePre: " + httpObject);
                            }

                            @Override
                            public void responsePost(HttpObject httpObject) {
                                System.out.println("*********** responsePost: " + httpObject);
                            }
                        };
                    }
                })
                .start();
    }

    @Override
    protected boolean isMITM() {
        return true;
    }
}
