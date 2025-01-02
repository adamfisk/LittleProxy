package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.littleshoot.proxy.extras.TestMitmManager;

import java.util.HashSet;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests just a single basic proxy running as a man in the middle.
 */
@NullMarked
public final class MitmProxyTest extends BaseProxyTest {
    private final Set<HttpMethod> requestPreMethodsSeen = new HashSet<>();
    private final Set<HttpMethod> requestPostMethodsSeen = new HashSet<>();
    private final StringBuilder responsePreBody = new StringBuilder();
    private final StringBuilder responsePostBody = new StringBuilder();
    private final Set<HttpMethod> responsePreOriginalRequestMethodsSeen = new HashSet<>();
    private final Set<HttpMethod> responsePostOriginalRequestMethodsSeen = new HashSet<>();

    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
                .withPort(0)
                .withManInTheMiddle(new TestMitmManager())
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @NonNull
                    @Override
                    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Nullable
                            @Override
                            public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPreMethodsSeen.add(((HttpRequest) httpObject).method());
                                }
                                return null;
                            }

                            @Nullable
                            @Override
                            public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
                                if (httpObject instanceof HttpRequest) {
                                    requestPostMethodsSeen.add(((HttpRequest) httpObject).method());
                                }
                                return null;
                            }

                            @Override
                            public HttpObject serverToProxyResponse(HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePreOriginalRequestMethodsSeen.add(originalRequest.method());
                                } else if (httpObject instanceof HttpContent) {
                                    responsePreBody.append(((HttpContent) httpObject).content().toString(UTF_8));
                                }
                                return httpObject;
                            }

                            @Override
                            public HttpObject proxyToClientResponse(
                                    HttpObject httpObject) {
                                if (httpObject instanceof HttpResponse) {
                                    responsePostOriginalRequestMethodsSeen.add(originalRequest.method());
                                } else if (httpObject instanceof HttpContent) {
                                    responsePostBody.append(((HttpContent) httpObject).content().toString(UTF_8));
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
    public void testSimpleGetRequest() {
        super.testSimpleGetRequest();
        assertMethodSeenInRequestFilters(HttpMethod.GET);
        assertMethodSeenInResponseFilters(HttpMethod.GET);
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimpleGetRequestOverHTTPS() {
        super.testSimpleGetRequestOverHTTPS();
        assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
        assertMethodSeenInRequestFilters(HttpMethod.GET);
        assertMethodSeenInResponseFilters(HttpMethod.GET);
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimplePostRequest() {
        super.testSimplePostRequest();
        assertMethodSeenInRequestFilters(HttpMethod.POST);
        assertMethodSeenInResponseFilters(HttpMethod.POST);
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimplePostRequestOverHTTPS() {
        super.testSimplePostRequestOverHTTPS();
        assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
        assertMethodSeenInRequestFilters(HttpMethod.POST);
        assertMethodSeenInResponseFilters(HttpMethod.POST);
        assertResponseFromFiltersMatchesActualResponse();
    }

    private void assertMethodSeenInRequestFilters(HttpMethod method) {
        assertThat(requestPreMethodsSeen)
          .as(method + " should have been seen in clientToProxyRequest filter")
          .contains(method);
        assertThat(requestPostMethodsSeen)
          .as(method + " should have been seen in proxyToServerRequest filter")
          .contains(method);
    }

    private void assertMethodSeenInResponseFilters(HttpMethod method) {
        assertThat(responsePreOriginalRequestMethodsSeen)
          .as(method + " should have been seen as the original request's method in serverToProxyResponse filter")
          .contains(method);
        assertThat(responsePostOriginalRequestMethodsSeen)
          .as(method + " should have been seen as the original request's method in proxyToClientResponse filter")
          .contains(method);
    }

    private void assertResponseFromFiltersMatchesActualResponse() {
      assertThat(lastResponse).as(responsePreBody.toString()).isEqualTo("Data received through HttpFilters.serverToProxyResponse should match response");
      assertThat(lastResponse).as(responsePostBody.toString()).isEqualTo("Data received through HttpFilters.proxyToClientResponse should match response");
    }

}
