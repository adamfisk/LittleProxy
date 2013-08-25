package org.littleshoot.proxy.impl;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponseDecoder;

import java.util.List;

class ProxyHttpResponseDecoder extends HttpResponseDecoder {

    ProxyHttpResponseDecoder() {
        super();
    }

    ProxyHttpResponseDecoder(int maxInitialLineLength,
            int maxHeaderSize,
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
