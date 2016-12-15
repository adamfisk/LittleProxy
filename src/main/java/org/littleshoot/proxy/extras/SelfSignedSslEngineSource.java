package org.littleshoot.proxy.extras;

import com.google.common.io.ByteStreams;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;

/**
 * Basic {@link SslEngineSource} for testing. The {@link SSLContext} uses
 * self-signed certificates that are generated lazily if the given key store
 * file doesn't yet exist.
 */
public class SelfSignedSslEngineSource implements SslEngineSource {
    private static final Logger LOG = LoggerFactory
            .getLogger(SelfSignedSslEngineSource.class);

    private static final String ALIAS = "littleproxy";
    private static final String PASSWORD = "Be Your Own Lantern";
    private static final String PROTOCOL = "TLS";
    private final File keyStoreFile;
    private final boolean trustAllServers;
    private final boolean sendCerts;

    private SSLContext sslContext;

    public SelfSignedSslEngineSource(String keyStorePath,
            boolean trustAllServers, boolean sendCerts) {
        this.trustAllServers = trustAllServers;
        this.sendCerts = sendCerts;
        this.keyStoreFile = new File(keyStorePath);
        initializeKeyStore();
        initializeSSLContext();
    }

    public SelfSignedSslEngineSource(String keyStorePath) {
        this(keyStorePath, false, true);
    }

    public SelfSignedSslEngineSource(boolean trustAllServers) {
        this(trustAllServers, true);
    }

    public SelfSignedSslEngineSource(boolean trustAllServers, boolean sendCerts) {
        this("littleproxy_keystore.jks", trustAllServers, sendCerts);
    }

    public SelfSignedSslEngineSource() {
        this(false);
    }

    @Override
    public SSLEngine newSslEngine() {
        return sslContext.createSSLEngine();
    }

    @Override
    public SSLEngine newSslEngine(String peerHost, int peerPort) {
        return sslContext.createSSLEngine(peerHost, peerPort);
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    private void initializeKeyStore() {
        if (keyStoreFile.isFile()) {
            LOG.info("Not deleting keystore");
            return;
        }

        nativeCall("keytool", "-genkey", "-alias", ALIAS, "-keysize",
                "4096", "-validity", "36500", "-keyalg", "RSA", "-dname",
                "CN=littleproxy", "-keypass", PASSWORD, "-storepass",
                PASSWORD, "-keystore", keyStoreFile.getName());

        nativeCall("keytool", "-exportcert", "-alias", ALIAS, "-keystore",
                keyStoreFile.getName(), "-storepass", PASSWORD, "-file",
                "littleproxy_cert");
    }

    private void initializeSSLContext() {
        String algorithm = Security
                .getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }

        try {
            final KeyStore ks = KeyStore.getInstance("JKS");
            // ks.load(new FileInputStream("keystore.jks"),
            // "changeit".toCharArray());
            ks.load(new FileInputStream(keyStoreFile), PASSWORD.toCharArray());

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(algorithm);
            kmf.init(ks, PASSWORD.toCharArray());

            // Set up a trust manager factory to use our key store
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(algorithm);
            tmf.init(ks);

            TrustManager[] trustManagers = null;
            if (!trustAllServers) {
                trustManagers = tmf.getTrustManagers();
            } else {
                trustManagers = new TrustManager[] { new X509TrustManager() {
                    // TrustManager that trusts all servers
                    @Override
                    public void checkClientTrusted(X509Certificate[] arg0,
                            String arg1)
                            throws CertificateException {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] arg0,
                            String arg1)
                            throws CertificateException {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                } };
            }
            
            KeyManager[] keyManagers = null;
            if (sendCerts) {
                keyManagers = kmf.getKeyManagers();
            } else {
                keyManagers = new KeyManager[0];
            }

            // Initialize the SSLContext to work with our key managers.
            sslContext = SSLContext.getInstance(PROTOCOL);
            sslContext.init(keyManagers, trustManagers, null);
        } catch (final Exception e) {
            throw new Error(
                    "Failed to initialize the server-side SSLContext", e);
        }
    }

    private String nativeCall(final String... commands) {
        LOG.info("Running '{}'", Arrays.asList(commands));
        final ProcessBuilder pb = new ProcessBuilder(commands);
        try {
            final Process process = pb.start();
            final InputStream is = process.getInputStream();

            byte[] data = ByteStreams.toByteArray(is);
            String dataAsString = new String(data);

            LOG.info("Completed native call: '{}'\nResponse: '" + dataAsString + "'",
                    Arrays.asList(commands));
            return dataAsString;
        } catch (final IOException e) {
            LOG.error("Error running commands: " + Arrays.asList(commands), e);
            return "";
        }
    }

}
