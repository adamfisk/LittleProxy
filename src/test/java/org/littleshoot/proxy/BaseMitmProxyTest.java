package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.Set;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * Base class for testing a single basic proxy running as a man in the middle.
 */
public abstract class BaseMitmProxyTest extends BaseProxyTest {
    private Set<HttpMethod> requestPreMethodsSeen = new HashSet<HttpMethod>();
    private Set<HttpMethod> requestPostMethodsSeen = new HashSet<HttpMethod>();
    private StringBuilder responsePreBody = new StringBuilder();
    private StringBuilder responsePostBody = new StringBuilder();
    private Set<HttpMethod> responsePreOriginalRequestMethodsSeen = new HashSet<HttpMethod>();
    private Set<HttpMethod> responsePostOriginalRequestMethodsSeen = new HashSet<HttpMethod>();

    protected MitmManager getMitmManager() {
        return new SelfSignedMitmManager();
    }

    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(0)
                .withManInTheMiddle(this.getMitmManager())
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

    protected void assertMethodSeenInRequestFilters(HttpMethod method) {
        assertThat(method
                        + " should have been seen in clientToProxyRequest filter",
                requestPreMethodsSeen, hasItem(method));
        assertThat(method
                        + " should have been seen in proxyToServerRequest filter",
                requestPostMethodsSeen, hasItem(method));
    }

    protected void assertMethodSeenInResponseFilters(HttpMethod method) {
        assertThat(
                method
                        + " should have been seen as the original requests's method in serverToProxyResponse filter",
                responsePreOriginalRequestMethodsSeen, hasItem(method));
        assertThat(
                method
                        + " should have been seen as the original requests's method in proxyToClientResponse filter",
                responsePostOriginalRequestMethodsSeen, hasItem(method));
    }

    protected void assertMethodNotSeenInRequestFilters(HttpMethod method) {
        assertThat(method
                        + " should have not been seen in clientToProxyRequest filter",
                requestPreMethodsSeen, not(hasItem(method)));
        assertThat(method
                        + " should have not been seen in proxyToServerRequest filter",
                requestPostMethodsSeen, not(hasItem(method)));
    }

    protected void assertMethodNotSeenInResponseFilters(HttpMethod method) {
        assertThat(
                method
                        + " should have not been seen as the original requests's method in serverToProxyResponse filter",
                responsePreOriginalRequestMethodsSeen, not(hasItem(method)));
        assertThat(
                method
                        + " should have not been seen as the original requests's method in proxyToClientResponse filter",
                responsePostOriginalRequestMethodsSeen, not(hasItem(method)));
    }

    protected void assertResponseFromFiltersMatchesActualResponse() {
        assertEquals(
                "Data received through HttpFilters.serverToProxyResponse should match response",
                lastResponse, responsePreBody.toString());
        assertEquals(
                "Data received through HttpFilters.proxyToClientResponse should match response",
                lastResponse, responsePostBody.toString());
    }

}
