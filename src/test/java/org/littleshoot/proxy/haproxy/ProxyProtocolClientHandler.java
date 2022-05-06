package org.littleshoot.proxy.haproxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;


public class ProxyProtocolClientHandler extends ChannelInboundHandlerAdapter {

    private static final String HOST = "http://localhost";
    private final int serverPort;
    private final ProxyProtocolHeader proxyProtocolHeader;

    ProxyProtocolClientHandler(int serverPort, ProxyProtocolHeader proxyProtocolHeader) {
        this.serverPort = serverPort;
        this.proxyProtocolHeader = proxyProtocolHeader;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
            ctx.write(getHAProxyHeader());
            ctx.writeAndFlush(getConnectRequest());
    }

    private HttpRequest getConnectRequest() {
        return new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, HOST + ":" + serverPort);
    }


    private String getHAProxyHeader() {
        return String.format("PROXY TCP4 %s %s %s %s\r\n", proxyProtocolHeader.getSourceAddress(), proxyProtocolHeader.getDestinationAddress(),
            proxyProtocolHeader.getSourcePort(), proxyProtocolHeader.getDestinationPort());
    }
}
