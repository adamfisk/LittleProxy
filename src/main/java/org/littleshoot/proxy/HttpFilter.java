package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Interface for rules for filtering HTTP traffic.
 */
public interface HttpFilter extends HttpRequestMatcher {

    /**
     * Filters the HTTP response.
     * 
     * @param request The HTTP request associated with the response.
     * @param response The response to filter.
     * @return The filtered response.
     */
    HttpResponse filterResponse(HttpRequest request, HttpResponse response);

    /**
     * Returns the maximum response size to expect in bytes for this filter.
     * You should set this as small as possible to save memory, but of course
     * not smaller than response body sizes will be.
     * 
     * @return The maximum response body size to support for this filter, 
     * in bytes.
     */
    int getMaxResponseSize();

}
