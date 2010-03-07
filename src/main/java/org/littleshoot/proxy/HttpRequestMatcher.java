package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Class for matching HTTP requests.
 */
public interface HttpRequestMatcher {

    /**
     * Returns whether or not to filter responses received from the specified
     * HTTP request.
     * 
     * @param httpRequest The request to check.
     * @return <code>true</code> if we should apply this set of rules, 
     * otherwise <code>false</code>.
     */
    boolean shouldFilterResponses(HttpRequest httpRequest);
}
