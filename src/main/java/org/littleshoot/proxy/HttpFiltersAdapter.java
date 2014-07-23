package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;

/**
 * Convenience base class for implementations of {@link HttpFilters}.
 */
public class HttpFiltersAdapter implements HttpFilters {
    protected final HttpRequest originalRequest;
    protected final ChannelHandlerContext ctx;

    public HttpFiltersAdapter(HttpRequest originalRequest,
            ChannelHandlerContext ctx) {
        this.originalRequest = originalRequest;
        this.ctx = ctx;
    }

    public HttpFiltersAdapter(HttpRequest originalRequest) {
        this(originalRequest, null);
    }

    @Override
    public HttpResponse clientToProxyRequestPreProcessing(HttpObject httpObject) {
        return null;
    }

    @Override
    public HttpResponse proxyToServerRequestPreProcessing(HttpObject httpObject) {
        return null;
    }

    @Override
    public void proxyToServerRequestSending() {
    }

    @Override
    public void proxyToServerRequestSent() {
    }

    @Override
    public HttpObject serverToProxyResponsePreProcessing(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public void serverToProxyResponseReceiving() {
    }

    @Override
    public void serverToProxyResponseReceived() {
    }

    @Override
    public HttpObject proxyToClientResponsePreProcessing(HttpObject httpObject) {
        return httpObject;
    }

    @Override
    public void proxyToServerAwaitingConnection() {
    }

    @Override
    public InetSocketAddress proxyToServerResolving(String resolvingServerHostAndPort) {
        return null;
    }

    @Override
    public void proxyToServerResolved(String serverHostAndPort, InetSocketAddress resolvedRemoteAddress) {
    }

    @Override
    public void proxyToServerConnecting() {
    }

    @Override
    public void proxyToServerSSLHandshaking() {
    }

    @Override
    public void proxyToServerConnectionFailed() {
    }

    @Override
    public void proxyToServerConnectionSuccess() {
    }
}
