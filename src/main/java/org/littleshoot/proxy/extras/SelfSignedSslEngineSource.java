package org.littleshoot.proxy.extras;

import iaik.asn1.structures.AlgorithmID;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Calendar;
import java.util.GregorianCalendar;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.io.IOUtils;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final String KEYSTORETYPE = "jks";
    
    private final File keyStoreFile;
    private final boolean trustAllServers;
    private final boolean sendCerts;

    private SSLContext sslContext;

	private KeyStore keyStore;

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
    
    public SSLEngine newSslEngine(SSLSession remoteServerSslSession) {
    	try{
    		
            javax.security.cert.X509Certificate[] remoteServerCertChain = remoteServerSslSession.getPeerCertificateChain();
            iaik.x509.X509Certificate remoteServerCertificate =  new iaik.x509.X509Certificate(remoteServerCertChain[0].getEncoded());
            Principal remoteServerDN = remoteServerCertificate.getSubjectDN();
            BigInteger remoteServerSerialNumber = remoteServerCertificate.getSerialNumber();
            
            
    	 	// You may find it useful to work from the comment skeleton below.
            SSLContext dynamicSslContext = SSLContext.getInstance("SSL");
            final char[] keyStorePassword = PASSWORD.toCharArray();
            final String keyStoreType = KEYSTORETYPE;
            String alias = ALIAS;
            
//            if (keyStoreFile != null) {
//                keyStore = KeyStore.getInstance(keyStoreType);
//                keyStore.load(new FileInputStream(keyStoreFile), keyStorePassword);
//                
//                this.ks = keyStore;
//            } else {
//                keyStore = null;
//                System.out.println("keystore is null!");
//            }

            // Get our key pair and our own DN (not the remote server's DN) from the keystore.
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, keyStorePassword);
            iaik.x509.X509Certificate certificate = new iaik.x509.X509Certificate(keyStore.getCertificate(alias).getEncoded());
            PublicKey publicKey = (PublicKey) certificate.getPublicKey();
            Principal ourDN = certificate.getSubjectDN();

            GregorianCalendar date = (GregorianCalendar) Calendar.getInstance();
            date.set(2013, 1, 1, 0, 0, 0);
            iaik.x509.X509Certificate serverCertificate = new iaik.x509.X509Certificate();
            
            serverCertificate.setSubjectDN(remoteServerDN);
            serverCertificate.setSerialNumber(remoteServerSerialNumber);
            serverCertificate.setIssuerDN(ourDN);

            serverCertificate.setValidNotBefore(date.getTime());
            date.add(Calendar.MONTH, 60);
            serverCertificate.setValidNotAfter(date.getTime());

            serverCertificate.setPublicKey(publicKey);

            if(privateKey.getAlgorithm().equals("DSA"))
                serverCertificate.sign(AlgorithmID.dsaWithSHA1, privateKey);
            else if(privateKey.getAlgorithm().equals("RSA"))
                serverCertificate.sign(AlgorithmID.sha1WithRSAEncryption, privateKey);
            else
                throw new RuntimeException("Unrecognized Signing Method!");


            KeyStore serverKeyStore = KeyStore.getInstance(keyStoreType);
            serverKeyStore.load(null, keyStorePassword);

            serverKeyStore.setCertificateEntry(alias, serverCertificate);
            serverKeyStore.setKeyEntry(alias, privateKey, keyStorePassword, new Certificate[] { serverCertificate });
            
            final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(serverKeyStore, keyStorePassword);

            dynamicSslContext.init(keyManagerFactory.getKeyManagers(),
                              new TrustManager[] { new TrustEveryone() },
                              null);
            
            return dynamicSslContext.createSSLEngine();
    	}catch(Exception e){
    		throw new RuntimeException("newSslEngine", e);
    	}
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
            this.keyStore = KeyStore.getInstance("JKS");
            // ks.load(new FileInputStream("keystore.jks"),
            // "changeit".toCharArray());
            keyStore.load(new FileInputStream(keyStoreFile), PASSWORD.toCharArray());

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf =
                    KeyManagerFactory.getInstance(algorithm);
            kmf.init(keyStore, PASSWORD.toCharArray());

            // Set up a trust manager factory to use our key store
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(algorithm);
            tmf.init(keyStore);

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
            final String data = IOUtils.toString(is);
            LOG.info("Completed native call: '{}'\nResponse: '" + data + "'",
                    Arrays.asList(commands));
            return data;
        } catch (final IOException e) {
            LOG.error("Error running commands: " + Arrays.asList(commands), e);
            return "";
        }
    }
    
    
    /**
     * We're carrying out a MITM attack, we don't care whether the cert
     * chains are trusted or not ;-)
     *
     */
    private static class TrustEveryone implements javax.net.ssl.X509TrustManager
    {
        public void checkClientTrusted(java.security.cert.X509Certificate[] chain,
                                       String authenticationType) {
        }
        
        public void checkServerTrusted(java.security.cert.X509Certificate[] chain,
                                       String authenticationType) {
        }

        public java.security.cert.X509Certificate[] getAcceptedIssuers()
        {
            return null;
        }
    }

}
