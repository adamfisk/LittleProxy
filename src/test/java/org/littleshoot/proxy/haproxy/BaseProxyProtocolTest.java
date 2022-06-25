package org.littleshoot.proxy.haproxy;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageDecoder;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.After;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.net.InetSocketAddress;

/**
 * Base for running Proxy protocol tests.
 * Proxy Protocol tests need special client and servers that are
 * capable of emitting and consuming proxy protocol headers.
 */
public abstract class BaseProxyProtocolTest {

    private EventLoopGroup childGroup;
    private EventLoopGroup parentGroup;
    private EventLoopGroup clientWorkGroup;
    private ProxyProtocolServerHandler proxyProtocolServerHandler;
    private HttpProxyServer proxyServer;
    private int proxyPort;
    private boolean acceptProxy = true;
    private boolean sendProxy = true;
    int serverPort;
    static final String SOURCE_ADDRESS = "192.168.0.153";
    static final String DESTINATION_ADDRESS = "192.168.0.154";
    static final String SOURCE_PORT = "123";
    static final String DESTINATION_PORT = "456";


    public void setup(boolean acceptProxy, boolean sendProxy) throws Exception {
        this.acceptProxy = acceptProxy;
        this.sendProxy = sendProxy;
        startProxyServer();
        startServer();
        startClient();
    }

    void startServer() {
        parentGroup = new NioEventLoopGroup();
        childGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(parentGroup, childGroup)
                .channelFactory(NioServerSocketChannel::new)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    public void initChannel(SocketChannel ch) {
                        proxyProtocolServerHandler = new ProxyProtocolServerHandler();
                        ch.pipeline().addLast(new HAProxyMessageDecoder()).addLast(new HttpRequestDecoder()).addLast(proxyProtocolServerHandler);
                    }
                }).option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true);

        ChannelFuture f = b.bind(0)
                .awaitUninterruptibly();
        Throwable cause = f.cause();
        if (cause != null) {
            throw new RuntimeException(cause);
        }
        serverPort = ((InetSocketAddress) f.channel().localAddress()).getPort();
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                stopServer();
            }
        }, "stopServerHook"));
    }

    void startClient() throws Exception {
        String host = "localhost";
        clientWorkGroup = new NioEventLoopGroup();
        Bootstrap b = new Bootstrap();
        b.group(clientWorkGroup);
        b.channel(NioSocketChannel.class);
        b.option(ChannelOption.SO_KEEPALIVE, true);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            public void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new ReadTimeoutHandler(1));
                if (acceptProxy) {
                    ch.pipeline().addLast(new ProxyProtocolTestEncoder());
                }
                ch.pipeline().addLast(new HttpRequestEncoder()).addLast(new ProxyProtocolClientHandler(serverPort, getProxyProtocolHeader()));
            }
        });
        ChannelFuture f = b.connect(host, proxyPort).sync();
        f.channel().closeFuture().sync();
    }

    HAProxyMessage getRelayedHaProxyMessage() {
        return proxyProtocolServerHandler.getHaProxyMessage();
    }

    private void stopServer() {
        childGroup.shutdownGracefully();
        parentGroup.shutdownGracefully();
    }

    private void stopProxyServer() {
        proxyServer.abort();
    }

    private void startProxyServer() {
        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withAcceptProxyProtocol(acceptProxy)
                .withSendProxyProtocol(sendProxy)
                .start();
        proxyPort = proxyServer.getListenAddress().getPort();

    }

    private ProxyProtocolHeader getProxyProtocolHeader() {
        return new ProxyProtocolHeader(SOURCE_ADDRESS, DESTINATION_ADDRESS, SOURCE_PORT, DESTINATION_PORT);
    }

    @After
    public void tearDown() {
        stopServer();
        stopProxyServer();
    }
}
