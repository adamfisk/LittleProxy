package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Factory for {@link HttpFilters}.
 */
public interface HttpFiltersSource {
    /**
     * Return an {@link HttpFilters} object for this request if and only if we
     * want to filter the request and/or its responses.
     */
    HttpFilters filterRequest(HttpRequest originalRequest,
            ChannelHandlerContext ctx);

    /**
     * Indicate how many (if any) bytes to buffer for incoming
     * {@link HttpRequest}s. A value of 0 or less indicates that no buffering
     * should happen and that messages will be passed to the {@link HttpFilters}
     * request filtering methods chunk by chunk. A positive value will cause
     * LittleProxy to try an create a {@link FullHttpRequest} using the data
     * received from the client, with its content already decompressed (in case
     * the client was compressing it). If the request size exceeds the maximum
     * buffer size, the request will fail.
     */
    int getMaximumRequestBufferSizeInBytes();

    /**
     * Indicate how many (if any) bytes to buffer for incoming
     * {@link HttpResponse}s. A value of 0 or less indicates that no buffering
     * should happen and that messages will be passed to the {@link HttpFilters}
     * response filtering methods chunk by chunk. A positive value will cause
     * LittleProxy to try an create a {@link FullHttpResponse} using the data
     * received from the server, with its content already decompressed (in case
     * the server was compressing it). If the response size exceeds the maximum
     * buffer size, the response will fail.
     */
    int getMaximumResponseBufferSizeInBytes();
}
