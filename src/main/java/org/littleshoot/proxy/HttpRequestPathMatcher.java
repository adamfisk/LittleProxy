package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request rule that operates on the request path.
 */
public class HttpRequestPathMatcher implements HttpRequestMatcher {

    private final String path;
    
    /**
     * Creates a new URI rule.
     * 
     * @param path The path to match.
     */
    public HttpRequestPathMatcher(final String path) {
        this.path = path;
    }

    public boolean filterResponses(final HttpRequest httpRequest) {
        final String uri = httpRequest.getUri();
        return uri.startsWith(path);
    }
    
    @Override
    public String toString() {
        return "Request Matcher for: "+this.path;
    }
}
