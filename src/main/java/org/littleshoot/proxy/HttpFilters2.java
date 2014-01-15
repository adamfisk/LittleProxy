package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * <p>
 * Additional filters on top of {@link org.littleshoot.proxy.HttpFilters}
 * </p>
 *
 */
public interface HttpFilters2 extends HttpFilters {
    String parseHostAndPort(final HttpRequest httpRequest);

    Boolean authenticationRequired(final HttpRequest request);
}
