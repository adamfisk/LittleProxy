package org.littleshoot.proxy;

import io.netty.handler.codec.http.*;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;

import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests just a single basic proxy running as a man in the middle.
 */
public class MitmProxyTest extends BaseProxyTest {
    private Set<HttpMethod> requestPreMethodsSeen = new HashSet<HttpMethod>();
    private Set<HttpMethod> requestPostMethodsSeen = new HashSet<HttpMethod>();
    private StringBuilder responsePreBody = new StringBuilder();
    private StringBuilder responsePostBody = new StringBuilder();
    private Set<HttpMethod> responsePreOriginalRequestMethodsSeen = new HashSet<HttpMethod>();
    private Set<HttpMethod> responsePostOriginalRequestMethodsSeen = new HashSet<HttpMethod>();

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
                            public HttpResponse clientToProxyRequest(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPreMethodsSeen
                                            .add(((HttpRequest) httpObject)
                                                    .getMethod());
                                }
                                return null;
                            }

                            @Override
                            public HttpResponse proxyToServerRequest(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPostMethodsSeen
                                            .add(((HttpRequest) httpObject)
                                                    .getMethod());
                                }
                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePreOriginalRequestMethodsSeen
                                            .add(originalRequest.getMethod());
                                } else if (httpObject instanceof HttpContent) {
                                    responsePreBody.append(((HttpContent) httpObject)
                                            .content().toString(
                                                    Charset.forName("UTF-8")));
                                }
                                return httpObject;
                            }

                            @Override
                            public HttpObject proxyToClientResponse(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePostOriginalRequestMethodsSeen
                                            .add(originalRequest.getMethod());
                                } else if (httpObject instanceof HttpContent) {
                                    responsePostBody.append(((HttpContent) httpObject)
                                            .content().toString(
                                                    Charset.forName("UTF-8")));
                                }
                                return httpObject;
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

    @Override
    public void testSimpleGetRequest() throws Exception {
        super.testSimpleGetRequest();
        assertMethodSeenInRequestFilters(HttpMethod.GET);
        assertMethodSeenInResponseFilters(HttpMethod.GET);
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimpleGetRequestOverHTTPS() throws Exception {
        super.testSimpleGetRequestOverHTTPS();
        assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
        assertMethodSeenInRequestFilters(HttpMethod.GET);
        assertMethodSeenInResponseFilters(HttpMethod.GET);
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        assertMethodSeenInRequestFilters(HttpMethod.POST);
        assertMethodSeenInResponseFilters(HttpMethod.POST);
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimplePostRequestOverHTTPS() throws Exception {
        super.testSimplePostRequestOverHTTPS();
        assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
        assertMethodSeenInRequestFilters(HttpMethod.POST);
        assertMethodSeenInResponseFilters(HttpMethod.POST);
        assertResponseFromFiltersMatchesActualResponse();
    }

    private void assertMethodSeenInRequestFilters(HttpMethod method) {
        assertTrue(method
                + " should have been seen in clientToProxyRequest filter",
                requestPreMethodsSeen.contains(method));
        assertTrue(method
                + " should have been seen in proxyToServerRequest filter",
                requestPostMethodsSeen.contains(method));
    }

    private void assertMethodSeenInResponseFilters(HttpMethod method) {
        assertTrue(
                method
                        + " should have been seen as the original requests's method in serverToProxyResponse filter",
                responsePreOriginalRequestMethodsSeen.contains(method));
        assertTrue(
                method
                        + " should have been seen as the original requests's method in proxyToClientResponse filter",
                responsePostOriginalRequestMethodsSeen.contains(method));
    }

    private void assertResponseFromFiltersMatchesActualResponse() {
        assertEquals(
                "Data received through HttpFilters.serverToProxyResponse should match response",
                lastResponse, responsePreBody.toString());
        assertEquals(
                "Data received through HttpFilters.proxyToClientResponse should match response",
                lastResponse, responsePostBody.toString());
    }

}
