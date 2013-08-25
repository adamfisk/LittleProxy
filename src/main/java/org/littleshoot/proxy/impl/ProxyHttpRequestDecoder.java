package org.littleshoot.proxy.impl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequestDecoder;

import java.util.List;

class ProxyHttpRequestDecoder extends HttpRequestDecoder {

    ProxyHttpRequestDecoder() {
        super();
    }

    ProxyHttpRequestDecoder(int maxInitialLineLength, int maxHeaderSize,
            int maxChunkSize) {
        super(maxInitialLineLength, maxHeaderSize, maxChunkSize);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer,
            List<Object> out) throws Exception {
        super.decode(ctx, buffer, out);
        int bytesRead = buffer.readerIndex();
        out.add(new ConnectionTracer(bytesRead));
    }

}
