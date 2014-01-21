package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

/**
 * <p>
 * Additional filters on top of {@link org.littleshoot.proxy.HttpFilters}
 * </p>
 */
public interface HttpFilters2 extends HttpFilters {
    /**
     *
     *
     * @param httpRequest
     * @return
     */
    String parseHostAndPort(final HttpRequest httpRequest);

    /**
     * Return false to allow the normal LittleProxy processing which
     * checks for Proxy-Authorization. Return true to skip this processing.
     * If you're not sure what to return, just return false.
     *
     * @param request Incoming request.
     * @return False to allow LittleProxy to process Proxy-Authorization,
     * true to skip that processing.
     */
    boolean skipProxyAuthorizationProcessing(final HttpRequest request);
}
