package org.littleshoot.proxy;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.jboss.netty.handler.codec.http.HttpRequest;
import org.junit.Test;

/**
 * Test for HTTP request URI rules.
 */
public class HttpRequestUriRuleTest {

    @Test public void testMatching() throws Exception {
        final HttpRequestPathMatcher matcher = 
            new HttpRequestPathMatcher("/search");

        final HttpRequest httpRequest = createRequest("/search?hl=en&source=hp&q=bop&aq=f&aqi=g10&aql=&oq=");
        
        assertTrue("Rule should have matches request",
            matcher.filterResponses(httpRequest));
    }

    private HttpRequest createRequest(String uri) {
        final HttpRequest httpRequest = mock(HttpRequest.class);
        when(httpRequest.getUri()).thenReturn(uri);
        return httpRequest;
    }
}
