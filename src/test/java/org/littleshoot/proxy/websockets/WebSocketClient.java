package org.littleshoot.proxy.websockets;

import com.google.common.base.Preconditions;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.example.http.websocketx.client.WebSocketClientHandler;
import io.netty.handler.codec.http.EmptyHttpHeaders;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshakerFactory;
import io.netty.handler.codec.http.websocketx.WebSocketVersion;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.logging.LoggingHandler;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Simple WebSocket client for use in unit tests that sends and receives text
 * frames.
 */
public class WebSocketClient {
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_AGGREGATOR_CONTENT_LENGTH = 65536;
    private static final int MAX_PAYLOAD_FRAME_LENGTH = 1280000;
    private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);

    // According to RFC-6455 (https://tools.ietf.org/html/rfc6455#section-3)
    // only the ws and wss schemes should be used, but some applications incorrectly
    // use http/https anyway, so we allow both for testing those edge cases
    private static final Set<String> SECURE_SCHEMES = Set.of("wss", "https");

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private final BlockingQueue<String> receivedMessages = new LinkedBlockingQueue<>();
    private Channel channel;
    private EventLoopGroup group;

    public void open(final URI uri, final Duration connectTimeout, final Optional<InetSocketAddress> httpProxy) throws TimeoutException {
        logger.info("{} connecting to {} via proxy {}", getClass().getSimpleName(), uri, httpProxy.map(Object::toString).orElse("(none)"));
        final boolean isSecure = SECURE_SCHEMES.contains(uri.getScheme());
        boolean connectionComplete = false;
        lock.writeLock().lock();
        try {
            if (channel != null) {
                throw new IllegalStateException("Client already open");
            }

            group = new NioEventLoopGroup();

            final WebSocketFrameReader handler = new WebSocketFrameReader(
                    WebSocketClientHandshakerFactory.newHandshaker(uri, WebSocketVersion.V13, null, false,
                            EmptyHttpHeaders.INSTANCE, MAX_PAYLOAD_FRAME_LENGTH, true, false, -1,
                            httpProxy.isPresent()));

            final Bootstrap bootstrap = new Bootstrap().group(group).channel(NioSocketChannel.class)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) CONNECT_TIMEOUT.toMillis())
                    .handler(new WebSocketClientChannelInitializer(handler, uri, httpProxy));

            /*
             * If using a proxy we connect directly to the proxy for non-secure schemes. For
             * secure schemes we add a proxy handler that uses HTTP CONNECT, so the
             * bootstrap just needs to connect as if it's communicating directly with the
             * origin server.
             */
            final ChannelFuture connectFuture;
            if (httpProxy.isPresent() && !isSecure) {
                connectFuture = bootstrap.connect(httpProxy.get().getHostString(), httpProxy.get().getPort());
            } else {
                connectFuture = bootstrap.connect(uri.getHost(), uri.getPort());
            }

            if (!connectFuture.awaitUninterruptibly(connectTimeout.toMillis())) {
                throw new TimeoutException("Connection timed out after " + connectTimeout);
            }
            channel = connectFuture.channel();
            if (!handler.handshakeFuture().awaitUninterruptibly(connectTimeout.toMillis())) {
                throw new TimeoutException("Handshake timed out after " + connectTimeout);
            }
            connectionComplete = true;
        } finally {
            if (!connectionComplete) {
                close();
            }
            lock.writeLock().unlock();
        }
    }

    public ChannelFuture send(final String value) {
        lock.readLock().lock();
        try {
            return channel.writeAndFlush(new TextWebSocketFrame(value));
        } finally {
            lock.readLock().unlock();
        }
    }

    public String waitForResponse(final Duration timeout) throws InterruptedException {
        return receivedMessages.poll(timeout.toMillis(), MILLISECONDS);
    }

    public void close() {
        lock.writeLock().lock();
        try {
            if (channel == null) {
                return;
            }
            channel.writeAndFlush(new CloseWebSocketFrame());
            channel.close();
            group.shutdownGracefully();
            channel = null;
            group = null;
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static class WebSocketClientChannelInitializer extends ChannelInitializer<SocketChannel> {
        private final WebSocketClientHandler handler;
        private final URI uri;
        private final Optional<InetSocketAddress> httpProxy;

        public WebSocketClientChannelInitializer(final WebSocketClientHandler handler, final URI uri,
                final Optional<InetSocketAddress> httpProxy) {
            this.handler = Preconditions.checkNotNull(handler);
            this.uri = Preconditions.checkNotNull(uri);
            this.httpProxy = Preconditions.checkNotNull(httpProxy);
        }

        @Override
        public void initChannel(final SocketChannel ch) throws Exception {
            ChannelPipeline pipeline = ch.pipeline();
            if (SECURE_SCHEMES.contains(uri.getScheme())) {
                httpProxy.ifPresent(proxyAddress -> pipeline.addFirst(new HttpProxyHandler(proxyAddress)));
                final SslContext sslContext = SslContextBuilder.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                final SslHandler sslHandler = sslContext.newHandler(ch.alloc(), uri.getHost(), uri.getPort());
                sslHandler.setHandshakeTimeoutMillis(CONNECT_TIMEOUT.toMillis());
                pipeline.addLast("ssl-handler", sslHandler);
            }
            pipeline.addLast("logging", new LoggingHandler(LogLevel.DEBUG));
            pipeline.addLast("http-codec", new HttpClientCodec());
            pipeline.addLast("http-aggregator", new HttpObjectAggregator(MAX_AGGREGATOR_CONTENT_LENGTH));
            pipeline.addLast("ws-handler", handler);
        }
    }

    private class WebSocketFrameReader extends WebSocketClientHandler {

        public WebSocketFrameReader(final WebSocketClientHandshaker handshaker) {
            super(handshaker);
        }

        @Override
        public void channelRead0(final ChannelHandlerContext ctx, final Object msg) throws Exception {
            if (msg instanceof TextWebSocketFrame) {
                final TextWebSocketFrame textFrame = (TextWebSocketFrame) msg;
                receivedMessages.offer(textFrame.text());
            }
            super.channelRead0(ctx, msg);
        }
    }
}
