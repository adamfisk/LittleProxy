package org.littleshoot.proxy.extras;

import com.google.common.io.ByteStreams;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.*;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.Security;
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

    private static final String PROTOCOL = "TLS";

    private final String alias;
    private final String password;
    private final String keyStoreFile;
    private final boolean trustAllServers;
    private final boolean sendCerts;

    private SSLContext sslContext;

    public SelfSignedSslEngineSource(String keyStorePath, boolean trustAllServers, boolean sendCerts,
        String alias, String password) {
        this.trustAllServers = trustAllServers;
        this.sendCerts = sendCerts;
        this.keyStoreFile = keyStorePath;
        this.alias = alias;
        this.password = password;
        initializeSSLContext();
    }

    public SelfSignedSslEngineSource(String keyStorePath, boolean trustAllServers, boolean sendCerts) {
        this(keyStorePath, trustAllServers, sendCerts, "littleproxy", "Be Your Own Lantern");
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

    private void initializeKeyStore(String filename) {
        nativeCall("keytool", "-genkey", "-alias", alias, "-keysize",
                "4096", "-validity", "36500", "-keyalg", "RSA", "-dname",
                "CN=littleproxy", "-keypass", password, "-storepass",
                password, "-keystore", filename);

        nativeCall("keytool", "-exportcert", "-alias", alias, "-keystore",
                filename, "-storepass", password, "-file",
                "littleproxy_cert");
    }

    private void initializeSSLContext() {
        String algorithm = Security
                .getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }

        try {
            final KeyStore ks = loadKeyStore();

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(algorithm);
            kmf.init(ks, password.toCharArray());

            // Set up a trust manager factory to use our key store
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(algorithm);
            tmf.init(ks);

            TrustManager[] trustManagers;
            if (!trustAllServers) {
                trustManagers = tmf.getTrustManagers();
            } else {
                trustManagers = new TrustManager[] { new X509TrustManager() {
                    // TrustManager that trusts all servers
                    @Override
                    public void checkClientTrusted(X509Certificate[] arg0, String arg1) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] arg0, String arg1) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                } };
            }
            
            KeyManager[] keyManagers;
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

    private KeyStore loadKeyStore() throws IOException, GeneralSecurityException {
        final KeyStore keyStore = KeyStore.getInstance("JKS");
        URL resourceUrl = getClass().getResource(keyStoreFile);
        if(resourceUrl != null) {
            loadKeyStore(keyStore, resourceUrl);
        } else {
            File keyStoreLocalFile = new File(keyStoreFile);
            if(!keyStoreLocalFile.isFile()) {
                initializeKeyStore(keyStoreLocalFile.getName());
            }
            loadKeyStore(keyStore, keyStoreLocalFile.toURI().toURL());
        }
        return keyStore;
    }

    private void loadKeyStore(KeyStore keyStore, URL url) throws IOException, GeneralSecurityException {
        try(InputStream is = url.openStream()) {
            keyStore.load(is, password.toCharArray());
        }
    }

    private String nativeCall(final String... commands) {
        LOG.info("Running '{}'", Arrays.asList(commands));
        final ProcessBuilder pb = new ProcessBuilder(commands);
        try {
            final Process process = pb.start();
            byte[] data;
            try (InputStream is = process.getInputStream()) {
                data = ByteStreams.toByteArray(is);
            }
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
