package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

public class ProxyHttpResponse {

    private final HttpObject response;
    private final HttpRequest httpRequest;
    private final HttpResponse httpResponse;

    public ProxyHttpResponse(final HttpRequest httpRequest, 
        final HttpResponse httpResponse, final HttpObject response) {
        this.httpRequest = httpRequest;
        this.httpResponse = httpResponse;
        this.response = response;
    }

    public HttpObject getResponse() {
        return response;
    }

    public HttpRequest getHttpRequest() {
        return this.httpRequest;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

}
