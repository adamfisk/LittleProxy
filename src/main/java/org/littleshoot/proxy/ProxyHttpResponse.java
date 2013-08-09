package org.littleshoot.proxy;

import io.netty.handler.codec.DecoderResult;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

public class ProxyHttpResponse implements HttpObject {

    private final HttpObject response;
    private final HttpRequest httpRequest;
    private final HttpResponse httpResponse;
    private DecoderResult decoderResult;

    public ProxyHttpResponse(final HttpRequest httpRequest,
            final HttpResponse httpResponse, final HttpObject response) {
        this.httpRequest = httpRequest;
        this.httpResponse = httpResponse;
        this.response = response;
        if (response instanceof HttpContent) {
            // Retain the content of the original response so that we can pass
            // it back to the browser.
            ((HttpContent) response).content().retain();
        }
    }

    public HttpObject getResponse() {
        return response;
    }

    @Override
    public void setDecoderResult(DecoderResult result) {
        this.decoderResult = result;
    }

    @Override
    public DecoderResult getDecoderResult() {
        return decoderResult;
    }

    public HttpRequest getHttpRequest() {
        return this.httpRequest;
    }

    public HttpResponse getHttpResponse() {
        return httpResponse;
    }

}
