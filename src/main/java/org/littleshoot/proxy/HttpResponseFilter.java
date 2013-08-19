package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that process responses.
 */
public interface HttpResponseFilter {

    /**
     * Filters the response, modifying it in place.
     * 
     * @param request
     *            The request associated with the response.
     * @param response
     *            The response to process.
     */
    void filterResponse(HttpRequest request, HttpResponse response);
}
