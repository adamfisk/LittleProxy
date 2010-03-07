package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for rules for filtering HTTP traffic.
 */
public interface HttpFilter extends HttpRequestMatcher {

    /**
     * Filters the HTTP response.
     * 
     * @param response The response to filter.
     * @return The filtered response.
     */
    HttpResponse filterResponse(HttpResponse response);

}
