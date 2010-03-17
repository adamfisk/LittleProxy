package org.littleshoot.proxy;

import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class ProxyHttpResponse {

    private final Object response;
    private final HttpRequest httpRequest;
    private final HttpResponse httpResponse;

    public ProxyHttpResponse(final HttpRequest httpRequest, 
        final HttpResponse httpResponse, final Object response) {
        this.httpRequest = httpRequest;
        this.httpResponse = httpResponse;
        this.response = response;
    }

    public Object getResponse() {
        return response;
    }

    public HttpRequest getHttpRequest() {
        return this.httpRequest;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

}
