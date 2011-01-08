package org.littleshoot.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.net.ssl.TrustManager;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * KeyStore manager that automatically generates a self-signed certificate
 * on startup if it doesn't already exit.
 */
public class SelfSignedKeyStoreManager implements KeyStoreManager {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private final File KEYSTORE_FILE = new File("littleproxy_keystore.jks");
    
    private final String AL = "littleproxy";
    
    private static final String PASS = "Be Your Own Lantern";

    public SelfSignedKeyStoreManager() {
        this(true);
    }
    
    public SelfSignedKeyStoreManager(final boolean regenerate) {
        resetStores();
    }
    
    private void resetStores() {
        if (KEYSTORE_FILE.isFile()) {
            log.info("Not deleting keystore");
            return;
        }
        
        nativeCall("keytool", "-genkey", "-alias", AL, "-keysize", 
            "4096", "-validity", "36500", "-keyalg", "RSA", "-dname", 
            "CN=littleproxy", "-keypass", PASS, "-storepass", 
            PASS, "-keystore", KEYSTORE_FILE.getName());
        
        nativeCall("keytool", "-exportcert", "-alias", AL, "-keystore", 
            KEYSTORE_FILE.getName(), "-storepass", PASS, "-file", 
            "littleproxy_cert");
    }

    public String getBase64Cert() {
        return "";
    }

    public InputStream keyStoreAsInputStream() {
        try {
            return new FileInputStream(KEYSTORE_FILE);
        } catch (final FileNotFoundException e) {
            throw new Error("Could not find keystore file!!");
        }
    }
    
    public InputStream trustStoreAsInputStream() {
        return null;
    }

    public char[] getCertificatePassword() {
        return PASS.toCharArray();
    }

    public char[] getKeyStorePassword() {
        return PASS.toCharArray();
    }
    
    public void addBase64Cert(final String alias, final String base64Cert) {
    }

    private String nativeCall(final String... commands) {
        log.info("Running '{}'", Arrays.asList(commands));
        final ProcessBuilder pb = new ProcessBuilder(commands);
        try {
            final Process process = pb.start();
            final InputStream is = process.getInputStream();
            final String data = IOUtils.toString(is);
            log.info("Completed native call: '{}'\nResponse: '"+data+"'", 
                Arrays.asList(commands));
            return data;
        } catch (final IOException e) {
            log.error("Error running commands: " + Arrays.asList(commands), e);
            return "";
        }
    }

    public TrustManager[] getTrustManagers() {
        // We don't use client authentication, so we should not need trust
        // managers.
        return null;
    }

}
