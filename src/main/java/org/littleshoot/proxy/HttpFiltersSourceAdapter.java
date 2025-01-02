package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Convenience base class for implementations of {@link HttpFiltersSource}.
 */
@NullMarked
public class HttpFiltersSourceAdapter implements HttpFiltersSource {

    @Nullable
    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
        return new HttpFiltersAdapter(originalRequest, null);
    }

    @Override
    @Nullable
    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest, @NonNull ChannelHandlerContext ctx) {
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
