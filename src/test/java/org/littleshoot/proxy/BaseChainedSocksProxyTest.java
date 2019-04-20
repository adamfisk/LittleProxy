package org.littleshoot.proxy;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.socksproxy.SocksServerInitializer;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;

import java.net.InetSocketAddress;

import static org.junit.Assert.fail;

abstract public class BaseChainedSocksProxyTest extends BaseProxyTest {
    private EventLoopGroup socksBossGroup;
    private EventLoopGroup socksWorkerGroup;
    private int socksPort;

    abstract protected ChainedProxyType getSocksProxyType();

    @Override
    protected void setUp() throws Exception {
        initializeSocksServer();
        this.proxyServer = bootstrapProxy()
                .withName("Downstream")
                .withPort(0)
                .withChainProxyManager(chainedProxyManager())
                .start();
    }

    @Override
    protected void tearDown() {
        if (socksBossGroup != null) {
            socksBossGroup.shutdownGracefully();
        }
        if (socksWorkerGroup != null) {
            socksWorkerGroup.shutdownGracefully();
        }
    }

    private void initializeSocksServer() throws Exception {
        socksBossGroup = new NioEventLoopGroup(1);
        socksWorkerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(socksBossGroup, socksWorkerGroup)
         .channel(NioServerSocketChannel.class)
         .handler(new LoggingHandler(LogLevel.DEBUG))
         .childHandler(new SocksServerInitializer());

        ChannelFuture channelFuture = bootstrap.bind(0).sync();
        socksPort = ((InetSocketAddress)channelFuture.channel().localAddress()).getPort();
    }

    private ChainedProxyManager chainedProxyManager() {
        return (httpRequest, chainedProxies, details) -> chainedProxies.add(new ChainedProxyAdapter() {
            @Override
            public InetSocketAddress getChainedProxyAddress() {
                return new InetSocketAddress("127.0.0.1", socksPort);
            }
            @Override
            public ChainedProxyType getChainedProxyType() {
                final ChainedProxyType socksProxyType = getSocksProxyType();
                switch (socksProxyType) {
                    case SOCKS4:
                    case SOCKS5:
                        return socksProxyType;
                    default:
                        fail(socksProxyType + " is not a type of SOCKS proxy");
                        throw new UnknownChainedProxyTypeException(socksProxyType);
                }
            }
        });
    }
}
