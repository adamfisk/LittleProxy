package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

/**
 * A rule for the host of an HTTP request.
 */
public class HttpRequestHostMatcher implements HttpRequestMatcher {

    private final String toCheck;

    /**
     * Creates a new host rule.
     * 
     * @param host The host string to match.
     */
    public HttpRequestHostMatcher(final String host) {
        // We don't include the "http" because it could be https.
        this.toCheck = "://" + host;
    }

    public boolean filterResponses(final HttpRequest httpRequest) {
        final String uri = httpRequest.getUri();
        return uri.contains(this.toCheck);
    }
    
}
