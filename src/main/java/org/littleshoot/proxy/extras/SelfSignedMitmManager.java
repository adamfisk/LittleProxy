package org.littleshoot.proxy.extras;

import org.littleshoot.proxy.MitmManager;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * {@link MitmManager} that uses self-signed certs for everything.
 */
public class SelfSignedMitmManager implements MitmManager {
    SelfSignedSslEngineSource selfSignedSslEngineSource =
            new SelfSignedSslEngineSource(true);

    @Override
    public SSLEngine serverSslEngine(String peerHost, int peerPort) {
        return selfSignedSslEngineSource.newSslEngine(peerHost, peerPort);
    }

    @Override
    public SSLEngine serverSslEngine() {
        return selfSignedSslEngineSource.newSslEngine();
    }

    @Override
    public SSLEngine clientSslEngineFor(SSLSession serverSslSession) {
        return selfSignedSslEngineSource.newSslEngine();
    }
}
