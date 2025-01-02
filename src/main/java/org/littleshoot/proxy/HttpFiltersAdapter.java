package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

import java.net.InetSocketAddress;

/**
 * Convenience base class for implementations of {@link HttpFilters}.
 */
@NullMarked
public class HttpFiltersAdapter implements HttpFilters {
    /**
     * A default, stateless, no-op {@link HttpFilters} instance.
     */
    public static final HttpFiltersAdapter NOOP_FILTER = new HttpFiltersAdapter(null);

    @Nullable protected final HttpRequest originalRequest;
    @Nullable protected final ChannelHandlerContext ctx;

    public HttpFiltersAdapter(@Nullable HttpRequest originalRequest, @Nullable ChannelHandlerContext ctx) {
        this.originalRequest = originalRequest;
        this.ctx = ctx;
    }

    public HttpFiltersAdapter(@Nullable HttpRequest originalRequest) {
        this(originalRequest, null);
    }

    @Nullable
    @Override
    public HttpResponse clientToProxyRequest(@NonNull HttpObject httpObject) {
        return null;
    }

    @Nullable
    @Override
    public HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
        return null;
    }

    @Override
    public void proxyToServerRequestSending() {
    }

    @Override
    public void proxyToServerRequestSent() {
    }

    @Override
    public HttpObject serverToProxyResponse(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public void serverToProxyResponseTimedOut() {
    }

    @Override
    public void serverToProxyResponseReceiving() {
    }

    @Override
    public void serverToProxyResponseReceived() {
    }

    @Override
    public HttpObject proxyToClientResponse(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public void proxyToServerConnectionQueued() {
    }

    @Nullable
    @Override
    public InetSocketAddress proxyToServerResolutionStarted(@NonNull String resolvingServerHostAndPort) {
        return null;
    }

    @Override
    public void proxyToServerResolutionFailed(@NonNull String hostAndPort) {
    }

    @Override
    public void proxyToServerResolutionSucceeded(@NonNull String serverHostAndPort,
                                                 @NonNull InetSocketAddress resolvedRemoteAddress) {
    }

    @Override
    public void proxyToServerConnectionStarted() {
    }

    @Override
    public void proxyToServerConnectionSSLHandshakeStarted() {
    }

    @Override
    public void proxyToServerConnectionFailed() {
    }

    @Override
    public void proxyToServerConnectionSucceeded(@NonNull ChannelHandlerContext serverCtx) {
    }

    @Override
    public boolean proxyToServerAllowMitm() {
        return true;
    }
}
