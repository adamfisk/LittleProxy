package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpRequest;

/**
 * Class for modifying HTTP requests.
 */
public interface HttpRequestFilter {

    /**
     * Makes any desired modifications to the request.
     * 
     * @param httpRequest The request.
     */
    void filter(HttpRequest httpRequest);

}
