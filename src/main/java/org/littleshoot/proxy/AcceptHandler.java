package org.littleshoot.proxy;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;

/**
 * <p>
 * Interface to manage the bytes read the first time after a client
 * connection has been accepted.
 * </p>
 */
public interface AcceptHandler {

    /**
     * Process the bytes coming from the first read performed on the
     * underlying channel after the connection has been accepted.
     * @param bytes
     *         the bytes read from the underlying channel
     * @return the bytes that will be processed by the proxy
     */
    ByteBuf process(ChannelHandlerContext ctx, ByteBuf bytes);

}
