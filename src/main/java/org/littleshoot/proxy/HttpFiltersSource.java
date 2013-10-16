package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Factory for {@link HttpFilters}.
 */
public interface HttpFiltersSource {
    /**
     * Return an {@link HttpFilters} object for this request if and only if we
     * want to filter the request and/or its responses.
     * 
     * @param originalRequest
     * @return
     */
    HttpFilters filterRequest(HttpRequest originalRequest);

    /**
     * Indicate how many (if any) bytes to buffer for incoming
     * {@link HttpRequest}s. A value of 0 or less indicates that no buffering
     * should happen and that messages will be passed to the {@link HttpFilters}
     * request filtering methods chunk by chunk.
     * 
     * @return
     */
    int getMaximumRequestBufferSizeInBytes();

    /**
     * Indicate how many (if any) bytes to buffer for incoming
     * {@link HttpResponse}s. A value of 0 or less indicates that no buffering
     * should happen and that messages will be passed to the {@link HttpFilters}
     * response filtering methods chunk by chunk.
     * 
     * @return
     */
    int getMaximumResponseBufferSizeInBytes();
}
