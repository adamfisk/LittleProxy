package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that process responses.
 */
public interface HttpResponseFilter {

    /**
     * Filters the response. The implementor can of course choose to return the
     * response unmodified.
     * 
     * @param request The request associated with the response.
     * @param response The response to process.
     * @return The processed response, possibly modified.
     */
    HttpResponse filterResponse(HttpRequest request, HttpResponse response);
}
