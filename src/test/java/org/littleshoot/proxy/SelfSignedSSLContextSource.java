package org.littleshoot.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.Security;
import java.util.Arrays;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic {@link SSLContextSource} for unit testing. The {@link SSLContext} uses
 * self-signed certificates that are generated automatically on startup.
 */
public class SelfSignedSSLContextSource implements SSLContextSource {
    private static final Logger LOG = LoggerFactory
            .getLogger(SelfSignedSSLContextSource.class);

    private static final File KEYSTORE_FILE = new File(
            "littleproxy_keystore.jks");
    private static final String ALIAS = "littleproxy";
    private static final String PASSWORD = "Be Your Own Lantern";
    private static final String PROTOCOL = "TLS";

    private SSLContext sslContext;

    public SelfSignedSSLContextSource() {
        initializeKeyStore();
        initializeSSLContext();
    }

    @Override
    public SSLContext getSSLContext() {
        return sslContext;
    }

    private void initializeKeyStore() {
        if (KEYSTORE_FILE.isFile()) {
            LOG.info("Not deleting keystore");
            return;
        }

        nativeCall("keytool", "-genkey", "-alias", ALIAS, "-keysize",
                "4096", "-validity", "36500", "-keyalg", "RSA", "-dname",
                "CN=littleproxy", "-keypass", PASSWORD, "-storepass",
                PASSWORD, "-keystore", KEYSTORE_FILE.getName());

        nativeCall("keytool", "-exportcert", "-alias", ALIAS, "-keystore",
                KEYSTORE_FILE.getName(), "-storepass", PASSWORD, "-file",
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
            ks.load(new FileInputStream(KEYSTORE_FILE), PASSWORD.toCharArray());

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(algorithm);
            kmf.init(ks, PASSWORD.toCharArray());

            // Initialize the SSLContext to work with our key managers.
            sslContext = SSLContext.getInstance(PROTOCOL);
            sslContext.init(kmf.getKeyManagers(), null, null);
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
            final String data = IOUtils.toString(is);
            LOG.info("Completed native call: '{}'\nResponse: '" + data + "'",
                    Arrays.asList(commands));
            return data;
        } catch (final IOException e) {
            LOG.error("Error running commands: " + Arrays.asList(commands), e);
            return "";
        }
    }

}
