package org.littleshoot.proxy.extras;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import org.littleshoot.proxy.MitmManager;

/**
 * {@link MitmManager} that uses the given host name to create a dynamic
 * certificate for. If a port is given, it will be truncated.
 */
public class HostNameMitmManager implements MitmManager {

    private BouncyCastleSslEngineSource sslEngineSource;

    public HostNameMitmManager(Authority authority)
            throws RootCertificateException {
        try {
            sslEngineSource = new BouncyCastleSslEngineSource(authority, true,
                    true);
        } catch (final Exception e) {
            throw new RootCertificateException(
                    "Errors during assembling root CA.", e);
        }
    }

    public SSLEngine serverSslEngine() {
        return sslEngineSource.newSslEngine();
    }

    public SSLEngine clientSslEngineFor(SSLSession serverSslSession,
            String serverHostAndPort) {
        try {
            String serverName = serverHostAndPort.split(":")[0];
            Collection<List<?>> subjectAlternativeNames = Collections
                    .emptyList();
            return sslEngineSource.createCertForHost(serverName,
                    subjectAlternativeNames);
        } catch (Exception e) {
            throw new FakeCertificateException(
                    "Creation dynamic certificate failed for "
                            + serverHostAndPort, e);
        }
    }
}
