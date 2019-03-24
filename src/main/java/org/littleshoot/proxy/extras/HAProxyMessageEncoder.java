package org.littleshoot.proxy.extras;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.haproxy.HAProxyMessage;

/**
 * Encodes an HAProxy proxy protocol header
 *
 * @see <a href="http://haproxy.1wt.eu/download/1.5/doc/proxy-protocol.txt">Proxy Protocol Specification</a>
 */
public class HAProxyMessageEncoder extends MessageToByteEncoder<HAProxyMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, HAProxyMessage msg, ByteBuf out) {
        out.writeBytes(getHaProxyMessage(msg));
    }

    private byte [] getHaProxyMessage(HAProxyMessage msg) {
        return String.format("%s %s %s %s %s %s\r\n", msg.command(), msg.proxiedProtocol(), msg.sourceAddress(), msg.destinationAddress(), msg.sourcePort(), msg.destinationPort()).getBytes();
    }

}
