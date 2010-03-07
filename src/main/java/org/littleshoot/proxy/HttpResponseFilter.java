package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that process responses.
 */
public interface HttpResponseFilter {

    /**
     * Processes the response.
     * 
     * @param response The response to process.
     * @param hostAndPort The host and port the response came from.
     * @return The processed response, possibly modified.
     */
    HttpResponse filterResponse(HttpResponse response);
}
