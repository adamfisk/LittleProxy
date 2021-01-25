package org.littleshoot.proxy.websockets;

import java.net.InetSocketAddress;
import java.security.cert.CertificateException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.net.ssl.SSLException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.example.http.websocketx.server.WebSocketFrameHandler;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;

/**
 * Simple WebSocket server for use in unit tests that receives text frames and
 * echoes them back after converting to upper case.
 */
public class WebSocketServer {
    static final String WEBSOCKET_PATH = "/websocket";
    private static final int MAX_AGGREGATOR_CONTENT_LENGTH = 65536;
    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);
    private final Lock lock = new ReentrantLock();
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel channel;

    public InetSocketAddress start(final boolean ssl, final Duration bindTimeout) throws CertificateException, SSLException, TimeoutException {
        lock.lock();
        try {
            if (bossGroup != null) {
                throw new IllegalStateException("Server already started");
            }

            final Optional<SslContext> sslCtx;
            if (ssl) {
                final SelfSignedCertificate ssc = new SelfSignedCertificate();
                sslCtx = Optional.of(SslContextBuilder.forServer(ssc.certificate(), ssc.privateKey()).build());
            } else {
                sslCtx = Optional.empty();
            }

            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            final ServerBootstrap bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class).handler(new LoggingHandler(LogLevel.DEBUG))
                    .childHandler(new WebSocketServerInitializer(sslCtx));

            final ChannelFuture bindFuture = bootstrap.bind("localhost", 0);
            if (!bindFuture.awaitUninterruptibly(bindTimeout.toMillis())) {
                throw new TimeoutException("Bind timed out after " + bindTimeout);
            }
            channel = bindFuture.channel();
            final InetSocketAddress serverAddress = (InetSocketAddress) channel.localAddress();
            logger.info("{} listening on {}", getClass().getSimpleName(), serverAddress);
            return serverAddress;
        } finally {
            lock.unlock();
        }
    }

    public void stop() throws InterruptedException {
        lock.lock();
        try {
            if (bossGroup == null) {
                return;
            }
            channel.close().sync();
            bossGroup.shutdownGracefully();
            workerGroup.shutdownGracefully();
            channel = null;
            bossGroup = null;
            workerGroup = null;
        } finally {
            lock.unlock();
        }
    }

    private class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {
        private final Optional<SslContext> sslCtx;

        public WebSocketServerInitializer(final Optional<SslContext> sslCtx) {
            this.sslCtx = Preconditions.checkNotNull(sslCtx);
        }

        @Override
        public void initChannel(final SocketChannel channel) throws Exception {
            final ChannelPipeline pipeline = channel.pipeline();
            sslCtx.map(ctx -> ctx.newHandler(channel.alloc()))
                    .ifPresent(handler -> pipeline.addLast("ssl", handler));
            pipeline.addLast("http-codec", new HttpServerCodec());
            pipeline.addLast("http-aggregator", new HttpObjectAggregator(MAX_AGGREGATOR_CONTENT_LENGTH));
            pipeline.addLast("ws-protocol", new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true));
            pipeline.addLast("ws-frame", new WebSocketFrameHandler());
        }
    }
}
