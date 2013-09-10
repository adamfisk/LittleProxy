package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

/**
 * Convenience base class for implementations of {@link HttpFiltersSource}.
 */
public class HttpFiltersSourceAdapter implements HttpFiltersSource {

    @Override
    public HttpFilters filterRequest(HttpRequest originalRequest) {
        return new HttpFiltersAdapter(originalRequest);
    }

    @Override
    public int getMaximumRequestBufferSizeInBytes() {
        return 0;
    }

    @Override
    public int getMaximumResponseBufferSizeInBytes() {
        return 0;
    }

}
