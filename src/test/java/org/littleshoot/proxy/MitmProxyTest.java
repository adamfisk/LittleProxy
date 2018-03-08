package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpMethod;

/**
 * Tests just a single basic proxy running as a man in the middle.
 */
public class MitmProxyTest extends BaseMitmProxyTest {
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
}
