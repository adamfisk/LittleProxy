package org.littleshoot.proxy;

import io.netty.handler.codec.http.*;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;

import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.hasItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * Tests a proxy that runs as a MITM and which is chained with
 * another proxy.
 */
public class MitmWithChainedProxyTest extends BaseChainedProxyTest {
    private Set<HttpMethod> requestPreMethodsSeen = new HashSet<>();
    private Set<HttpMethod> requestPostMethodsSeen = new HashSet<>();
    private StringBuilder responsePreBody = new StringBuilder();
    private StringBuilder responsePostBody = new StringBuilder();
    private Set<HttpMethod> responsePreOriginalRequestMethodsSeen = new HashSet<>();
    private Set<HttpMethod> responsePostOriginalRequestMethodsSeen = new HashSet<>();

    @Override
    protected void setUp() {

        REQUESTS_SENT_BY_DOWNSTREAM.set(0);
        REQUESTS_RECEIVED_BY_UPSTREAM.set(0);
        TRANSPORTS_USED.clear();
        this.upstreamProxy = upstreamProxy().start();

        this.proxyServer = bootstrapProxy()
                .withPort(0)
                .withChainProxyManager(chainedProxyManager())
                .plusActivityTracker(DOWNSTREAM_TRACKER)
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
                                                    .method());
                                }
                                return null;
                            }

                            @Override
                            public HttpResponse proxyToServerRequest(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPostMethodsSeen
                                            .add(((HttpRequest) httpObject)
                                                    .method());
                                }
                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePreOriginalRequestMethodsSeen
                                            .add(originalRequest.method());
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
                                            .add(originalRequest.method());
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
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.GET);
            assertMethodSeenInResponseFilters(HttpMethod.GET);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    @Override
    public void testSimpleGetRequestOverHTTPS() throws Exception {
        super.testSimpleGetRequestOverHTTPS();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
            assertMethodSeenInRequestFilters(HttpMethod.GET);
            assertMethodSeenInResponseFilters(HttpMethod.GET);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.POST);
            assertMethodSeenInResponseFilters(HttpMethod.POST);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    @Override
    public void testSimplePostRequestOverHTTPS() throws Exception {
        super.testSimplePostRequestOverHTTPS();
        if (isChained() && !expectBadGatewayForEverything()) {
            assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
            assertMethodSeenInRequestFilters(HttpMethod.POST);
            assertMethodSeenInResponseFilters(HttpMethod.POST);
            assertResponseFromFiltersMatchesActualResponse();
        }
    }

    private void assertMethodSeenInRequestFilters(HttpMethod method) {
        assertThat(method
                        + " should have been seen in clientToProxyRequest filter",
                requestPreMethodsSeen, hasItem(method));
        assertThat(method
                        + " should have been seen in proxyToServerRequest filter",
                requestPostMethodsSeen, hasItem(method));
    }

    private void assertMethodSeenInResponseFilters(HttpMethod method) {
        assertThat(
                method
                        + " should have been seen as the original requests's method in serverToProxyResponse filter",
                responsePreOriginalRequestMethodsSeen, hasItem(method));
        assertThat(
                method
                        + " should have been seen as the original requests's method in proxyToClientResponse filter",
                responsePostOriginalRequestMethodsSeen, hasItem(method));
    }

    private void assertResponseFromFiltersMatchesActualResponse() {
        assertEquals(
                "Data received through HttpFilters.serverToProxyResponse should match response",
                lastResponse, responsePreBody.toString());
        assertEquals(
                "Data received through HttpFilters.proxyToClientResponse should match response",
                lastResponse, responsePostBody.toString());
    }

    @Override
    protected void tearDown() {
        this.upstreamProxy.abort();
    }
}
