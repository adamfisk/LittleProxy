package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Interface for classes that process responses.
 */
public interface HttpResponseProcessor {

    /**
     * Processes the response.
     * 
     * @param response The response to process.
     * @param hostAndPort The host and port the response came from.
     * @return The processed response, possibly modified.
     */
    HttpResponse processResponse(HttpResponse response, String hostAndPort);
    
    HttpChunk processChunk(HttpChunk chunk, String hostAndPort);

}
