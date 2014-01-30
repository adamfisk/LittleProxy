package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Convenience base class for implementations of {@link HttpFiltersSource}.
 */
public class HttpFiltersSourceAdapter implements HttpFiltersSource {

    public HttpFilters filterRequest(HttpRequest originalRequest) {
        return new HttpFiltersAdapter(originalRequest, null);
    }
    
    @Override
    public HttpFilters filterRequest(HttpRequest originalRequest,
            ChannelHandlerContext ctx) {
        return filterRequest(originalRequest);
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
