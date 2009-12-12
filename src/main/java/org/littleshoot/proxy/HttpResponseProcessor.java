package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that process responses.
 */
public interface HttpResponseProcessor {

    /**
     * Processes the response.
     * 
     * @param response The response to process.
     * @return The processed response, possibly modified.
     */
    HttpResponse processResponse(HttpResponse response);

}
