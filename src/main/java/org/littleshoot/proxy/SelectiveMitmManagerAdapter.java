package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * Convenience base class for adapting a non-selective-{@link MitmManager} into a {@link SelectiveMitmManager}.
 */
public abstract class SelectiveMitmManagerAdapter implements SelectiveMitmManager {
    private final MitmManager childMitmManager;

    public SelectiveMitmManagerAdapter(MitmManager childMitmManager) {
        this.childMitmManager = childMitmManager;
    }

    @Override
    public SSLEngine serverSslEngine(String peerHost, int peerPort) {
        return this.childMitmManager.serverSslEngine(peerHost, peerPort);
    }

    @Override
    public SSLEngine serverSslEngine() {
        return this.childMitmManager.serverSslEngine();
    }

    @Override
    public SSLEngine clientSslEngineFor(HttpRequest httpRequest, SSLSession serverSslSession) {
        return this.childMitmManager.clientSslEngineFor(httpRequest, serverSslSession);
    }
}
