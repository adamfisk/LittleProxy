package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * {@link HttpResponseFilter} that just passes the response back without
 * modifying it in any way.
 */
public class NoOpHttpResponseFilter implements HttpResponseFilter {

    public HttpChunk processChunk(final HttpChunk chunk, 
        final String hostAndPort) {
        return chunk;
    }

    public HttpResponse filterResponse(final HttpResponse response) {
        return response;
    }
}
