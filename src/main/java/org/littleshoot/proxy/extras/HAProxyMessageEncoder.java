package org.littleshoot.proxy.extras;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encodes an HAProxy proxy protocol header
 *
 * @see <a href="http://haproxy.1wt.eu/download/1.5/doc/proxy-protocol.txt">Proxy Protocol Specification</a>
 */
public class HAProxyMessageEncoder extends MessageToByteEncoder<ProxyProtocolMessage> {

    @Override
    protected void encode(ChannelHandlerContext ctx, ProxyProtocolMessage msg, ByteBuf out) {
        out.writeBytes(getHaProxyMessage(msg));
    }

    private byte [] getHaProxyMessage(ProxyProtocolMessage msg) {
        return String.format("%s %s %s %s %s %s\r\n", msg.getCommand(), msg.getProxiedProtocol(), msg.getSourceAddress(), msg.getDestinationAddress(), msg.getSourcePort(),
            msg.getDestinationPort()).getBytes();
    }

}
