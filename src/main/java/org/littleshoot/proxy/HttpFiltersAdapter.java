package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * Convenience base class for implementations of {@link HttpFilters}.
 */
public class HttpFiltersAdapter implements HttpFilters {
    protected final HttpRequest originalRequest;

    public HttpFiltersAdapter(HttpRequest originalRequest) {
        super();
        this.originalRequest = originalRequest;
    }

    @Override
    public HttpResponse requestPre(HttpObject httpObject) {
        return null;
    }

    @Override
    public HttpResponse requestPost(HttpObject httpObject) {
        return null;
    }

    @Override
    public HttpObject responsePre(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public HttpObject responsePost(HttpObject httpObject) {
        return httpObject;
    }

}
