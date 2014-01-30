package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

/**
 * <p>
 * Additional filters on top of {@link org.littleshoot.proxy.HttpFilters}
 * </p>
 */
public interface HttpFilters2 extends HttpFilters {
    /**
     * By default, LittleProxy parses the host and port to proxy
     * back to by looking at
     * {@link io.netty.handler.codec.http.HttpRequest#getUri()}.
     * Override this method to parse the host and port in a different way.
     * Return null to process normally. If you're not sure what to return,
     * return null.
     *
     * @param httpRequest Incoming request.
     * @return Null to defer to LittleProxy's default host and port
     * parsing; otherwise, return the host and port in the form
     * host:port (wihout a scheme or any slashes).
     */
    String parseHostAndPort(final HttpRequest httpRequest);
}
