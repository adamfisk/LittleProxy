package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.HttpResponseDecoder;


public class  ProxyHttpResponseDecoder extends HttpResponseDecoder {
    private HttpRelayingHandler relayingHandler;

    /**
     * Creates a new instance with the default
     * {@code maxInitialLineLength (4096}}, {@code maxHeaderSize (8192)}, and
     * {@code maxChunkSize (8192)}.
     */
    public ProxyHttpResponseDecoder() {
        super();
    }

    /**
     * Creates a new instance with the specified parameters.
     */
    public ProxyHttpResponseDecoder(
            int maxInitialLineLength, int maxHeaderSize, int maxChunkSize, HttpRelayingHandler handler) {
        super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
        this.relayingHandler = handler;
    }

    @Override
    protected HttpMessage createMessage(String[] initialLine) {
        return new DefaultHttpResponse(
                HttpVersion.valueOf(initialLine[0]),
                new HttpResponseStatus(Integer.valueOf(initialLine[1]), initialLine[2]));
    }

    @Override
    protected boolean isDecodingRequest() {
        return false;
    }

    @Override
    protected boolean isContentAlwaysEmpty(HttpMessage msg) {
        HttpMethod method = relayingHandler.getMethodFromRequest();
        if (method == HttpMethod.HEAD) {
            return true;
        }
        return super.isContentAlwaysEmpty(msg);
    }
}

