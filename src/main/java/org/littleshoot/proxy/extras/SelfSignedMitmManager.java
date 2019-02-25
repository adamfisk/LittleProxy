package org.littleshoot.proxy.extras;

import io.netty.handler.codec.http.HttpRequest;
import org.littleshoot.proxy.MitmManager;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * {@link MitmManager} that uses self-signed certs for everything.
 */
public class SelfSignedMitmManager implements MitmManager {
    private final SelfSignedSslEngineSource selfSignedSslEngineSource;

    public SelfSignedMitmManager() {
        this.selfSignedSslEngineSource = new SelfSignedSslEngineSource(true);
    }

    public SelfSignedMitmManager(SelfSignedSslEngineSource selfSignedSslEngineSource) {
        this.selfSignedSslEngineSource = selfSignedSslEngineSource;
    }

    @Override
    public SSLEngine serverSslEngine(String peerHost, int peerPort) {
        return selfSignedSslEngineSource.newSslEngine(peerHost, peerPort);
    }

    @Override
    public SSLEngine serverSslEngine() {
        return selfSignedSslEngineSource.newSslEngine();
    }

    @Override
    public SSLEngine clientSslEngineFor(HttpRequest httpRequest, SSLSession serverSslSession) {
        return selfSignedSslEngineSource.newSslEngine();
    }
}
