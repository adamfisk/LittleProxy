package org.littleshoot.proxy;

import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.*;

public class SslContextFactory {

    private static final String PROTOCOL = "TLS";
    private final SSLContext SERVER_CONTEXT;
    private final SSLContext CLIENT_CONTEXT;

    public SslContextFactory(final KeyStoreManager ksm) {
        String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }

        SSLContext serverContext;
        try {
            final KeyStore ks = KeyStore.getInstance("JKS");
            //ks.load(new FileInputStream("keystore.jks"), "changeit".toCharArray());
            ks.load(ksm.keyStoreAsInputStream(),
                    ksm.getKeyStorePassword());

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf = 
                KeyManagerFactory.getInstance(algorithm);
            kmf.init(ks, ksm.getCertificatePassword());

            // Initialize the SSLContext to work with our key managers.
            serverContext = SSLContext.getInstance(PROTOCOL);
            serverContext.init(kmf.getKeyManagers(), null, null);
        } catch (final Exception e) {
            throw new Error(
                    "Failed to initialize the server-side SSLContext", e);
        }
        SERVER_CONTEXT = serverContext;

        SSLContext clientContext;
        try {
            clientContext = SSLContext.getInstance(PROTOCOL);
            final X509TrustManager allTrustingTrustManager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] x509Certificates, String s) throws CertificateException {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            final TrustManager[] trustManagers = LittleProxyConfig.isAcceptAllSSLCertificates() ?
                    new TrustManager[]{allTrustingTrustManager} : null;
            clientContext.init(null, trustManagers, null);
        } catch (final Exception e) {
            throw new Error(
                    "Failed to initialize the client-side SSLContext", e);
        }
        CLIENT_CONTEXT = clientContext;
    }

    public SSLContext getServerContext() {
        return SERVER_CONTEXT;
    }

    public SSLContext getClientContext() {
        return CLIENT_CONTEXT;
    }

}
