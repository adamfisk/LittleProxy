package org.littleshoot.proxy;

import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.LastHttpContent;

public abstract class HttpHandler extends
        SimpleChannelInboundHandler<HttpObject> {
    protected static boolean isLastContent(HttpObject httpObject) {
        return httpObject instanceof LastHttpContent;
    }
}
