package org.littleshoot.proxy;

import java.security.KeyStore;
import java.security.Security;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;

public class SslContextFactory {

    private static final String PROTOCOL = "TLS";
    private final SSLContext SERVER_CONTEXT;
    
    public SslContextFactory(final KeyStoreManager ksm) {
        this(ksm, null);
    }
    
    public SslContextFactory(final KeyStoreManager ksm, 
        final TrustManager[] trustManagers) {
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
            serverContext.init(kmf.getKeyManagers(), trustManagers, null);
        } catch (final Exception e) {
            throw new Error(
                    "Failed to initialize the server-side SSLContext", e);
        }

        SERVER_CONTEXT = serverContext;
    }


    public SSLContext getServerContext() {
        return SERVER_CONTEXT;
    }

}
