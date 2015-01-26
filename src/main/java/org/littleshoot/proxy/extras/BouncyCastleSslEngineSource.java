package org.littleshoot.proxy.extras;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.math.BigInteger;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.SignatureException;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.apache.commons.io.IOUtils;
import org.bouncycastle.asn1.ASN1Encodable;
import org.bouncycastle.asn1.DERObject;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.X509Extension;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.MiscPEMGenerator;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemWriter;
import org.bouncycastle.x509.extension.SubjectKeyIdentifierStructure;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.CacheBuilder;

/**
 * A {@link SslEngineSource} which creates a key store with a Root Certificate
 * Authority. The certificates are generated lazily if the given key store file
 * doesn't yet exist.
 */
public class BouncyCastleSslEngineSource implements SslEngineSource {

    private static final Logger LOG = LoggerFactory
            .getLogger(BouncyCastleSslEngineSource.class);

    static final String ALIAS = "mocuishle";
    static final char[] PASSWORD = "Be Your Own Lantern".toCharArray();
    private static final String SSL_CONTEXT_PROTOCOL = "TLS";
    private static final String KEY_ALGORITHM = "RSA";
    private static final String SECURE_RANDOM_ALGORITHM = "SHA1PRNG";
    private static final int ROOT_KEYSIZE = 2048;
    private static final int FAKE_KEYSIZE = 1024;
    private static final String SIGNATURE_ALGORITHM = "SHA1WithRSAEncryption";

    /**
     * The P12 format has to be implemented by every vendor. Oracles proprietary
     * JKS type is not available in Android.
     */
    private static final String KEY_STORE_TYPE = "PKCS12";

    /**
     * Root CA has to be installed in browsers. Hundred years should avoid
     * expiration.
     */
    private static final int VALIDITY = 36500;

    private final File keyStoreFile;
    private final boolean trustAllServers;
    private final boolean sendCerts;

    private SSLContext sslContext;

    private Certificate caCert;

    private PrivateKey caPrivKey;

    /**
     * A word about serial numbers ... There have to be different serial numbers
     * generated, cause if multiple certificates with different finger prints do
     * have the same serial from the same CA, the browser gets crazy. At least,
     * Firefox v3.x does.
     */
    private final AtomicLong serverCertificateSerial;

    /**
     * There is in fact a single instance of a SslEngineSource per proxy. So the
     * cache must be handled thread save. The dynamic contexts are expensive to
     * create, but doesn't have to be distinct.
     * 
     * To avoid locks, duplicated contexts are tolerated and ignored here.
     */
    private Map<Object, Object> serverSSLContexts;

    public BouncyCastleSslEngineSource(String keyStorePath,
            boolean trustAllServers, boolean sendCerts)
            throws RootCertificateException {
        this.trustAllServers = trustAllServers;
        this.sendCerts = sendCerts;
        this.keyStoreFile = new File(keyStorePath);
        this.serverCertificateSerial = initSerial();
        this.serverSSLContexts = CacheBuilder.newBuilder() //
                .expireAfterAccess(5, TimeUnit.MINUTES) //
                .concurrencyLevel(16) //
                .build().asMap();
        Security.addProvider(new BouncyCastleProvider());
        initializeKeyStore();
        initializeSSLContext();
    }

    private AtomicLong initSerial() {
        final Random rnd = new Random();
        rnd.setSeed(System.currentTimeMillis());
        // prevent browser certificate caches, cause of doubled serial numbers
        // using 48bit random number
        long sl = ((long) rnd.nextInt()) << 32 | (rnd.nextInt() & 0xFFFFFFFFL);
        // let reserve of 16 bit for increasing, serials have to be positive
        sl = sl & 0x0000FFFFFFFFFFFFL;
        return new AtomicLong(sl);
    }

    public SSLEngine newSslEngine() {
        return sslContext.createSSLEngine();
    }

    public SSLContext getSslContext() {
        return sslContext;
    }

    private void initializeKeyStore() throws RootCertificateException {
        if (keyStoreFile.exists()) {
            return;
        }
        try {
            MillisecondsDuration duration = new MillisecondsDuration();
            KeyStore keystore = createRootCA();
            LOG.info("Created root certificate authority key store in {}ms",
                    duration);

            OutputStream os = null;
            try {
                os = new FileOutputStream(keyStoreFile);
                keystore.store(os, PASSWORD);
            } finally {
                IOUtils.closeQuietly(os);
            }

            final Certificate cert = keystore.getCertificate(ALIAS);
            Writer sw = null;
            PemWriter pw = null;
            try {
                sw = new FileWriter(new File(keyStoreFile.getParent(),
                        "mocuishle.pem"));
                pw = new PemWriter(sw);
                pw.writeObject(new MiscPEMGenerator(cert));
                pw.flush();
            } finally {
                IOUtils.closeQuietly(pw);
                IOUtils.closeQuietly(sw);
            }
        } catch (Exception e) {
            throw new RootCertificateException(
                    "Error during creating root CA with bouncy castle", e);
        }
    }

    /**
     * Creates a new Root CA certificate and returns private and public key as
     * {@link KeyStore}. The key store type is PKCS12, which has to be
     * implemented by every vendor. This makes the file portable. Oracles
     * proprietary JKS type is not available in Android.
     * 
     * Derived from Zed Attack Proxy (ZAP). ZAP is an HTTP/HTTPS proxy for
     * assessing web application security. Copyright 2011 mawoki@ymail.com
     * Licensed under the Apache License, Version 2.0
     * 
     * @throws RootCertificateException
     * 
     * @see org.zaproxy.zap.extension.dynssl.SslCertificateUtils.createRootCA()
     */
    public KeyStore createRootCA() throws NoSuchAlgorithmException,
            RootCertificateException {
        final Date startDate = Calendar.getInstance().getTime();
        final Date expireDate = new Date(startDate.getTime()
                + (VALIDITY * 24L * 60L * 60L * 1000L));

        final KeyPairGenerator g = KeyPairGenerator.getInstance(KEY_ALGORITHM);
        g.initialize(ROOT_KEYSIZE,
                SecureRandom.getInstance(SECURE_RANDOM_ALGORITHM));
        final KeyPair keypair = g.genKeyPair();

        final PrivateKey privKey = keypair.getPrivate();
        final PublicKey pubKey = keypair.getPublic();

        X500NameBuilder namebld = new X500NameBuilder(BCStyle.INSTANCE);
        namebld.addRDN(BCStyle.CN, "Proxy for offline use");
        namebld.addRDN(BCStyle.O, "Mo Cuishle");
        namebld.addRDN(BCStyle.OU, "Certificate Authority");

        Random rnd = new Random();
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(
                namebld.build(), BigInteger.valueOf(rnd.nextInt()), startDate,
                expireDate, namebld.build(), pubKey);

        KeyStore ks = null;
        try {
            certGen.addExtension(X509Extension.subjectKeyIdentifier, false,
                    new SubjectKeyIdentifierStructure(pubKey));
            certGen.addExtension(X509Extension.basicConstraints, true,
                    new BasicConstraints(true));
            certGen.addExtension(X509Extension.keyUsage, false, new KeyUsage(
                    KeyUsage.keyCertSign | KeyUsage.digitalSignature
                            | KeyUsage.keyEncipherment
                            | KeyUsage.dataEncipherment | KeyUsage.cRLSign));

            Vector<DERObject> eku = new Vector<DERObject>(3, 1);
            eku.add(KeyPurposeId.id_kp_serverAuth);
            eku.add(KeyPurposeId.id_kp_clientAuth);
            eku.add(KeyPurposeId.anyExtendedKeyUsage);
            certGen.addExtension(X509Extension.extendedKeyUsage, false,
                    new ExtendedKeyUsage(eku));

            final ContentSigner sigGen = new JcaContentSignerBuilder(
                    SIGNATURE_ALGORITHM).setProvider("BC").build(privKey);
            final X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider("BC").getCertificate(certGen.build(sigGen));

            ks = KeyStore.getInstance(KEY_STORE_TYPE);
            ks.load(null, null);
            ks.setKeyEntry(ALIAS, privKey, PASSWORD, new Certificate[] { cert });

        } catch (final Exception e) {
            throw new RootCertificateException(
                    "Errors during assembling root CA.", e);
        }
        return ks;
    }

    private void initializeSSLContext() {
        try {
            final KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
            FileInputStream is = null;
            try {
                is = new FileInputStream(keyStoreFile);
                ks.load(is, PASSWORD);
            } finally {
                IOUtils.closeQuietly(is);
            }
            this.caCert = ks.getCertificate(ALIAS);
            this.caPrivKey = (PrivateKey) ks.getKey(ALIAS, PASSWORD);

            // Set up key manager factory to use our key store
            String keyManAlg = KeyManagerFactory.getDefaultAlgorithm();
            final KeyManagerFactory kmf = KeyManagerFactory
                    .getInstance(keyManAlg);
            kmf.init(ks, PASSWORD);

            // Set up a trust manager factory to use our key store
            String trustManAlg = TrustManagerFactory.getDefaultAlgorithm();
            TrustManagerFactory tmf = TrustManagerFactory
                    .getInstance(trustManAlg);
            tmf.init(ks);

            TrustManager[] trustManagers = null;
            if (!trustAllServers) {
                trustManagers = tmf.getTrustManagers();
            } else {
                trustManagers = new TrustManager[] { new X509TrustManager() {
                    // TrustManager that trusts all servers
                    public void checkClientTrusted(X509Certificate[] chain,
                            String authType) throws CertificateException {
                        LOG.debug("X509TrustManager.checkClientTrusted {} {}",
                                chain, authType);
                    }

                    public void checkServerTrusted(X509Certificate[] chain,
                            String authType) throws CertificateException {
                        LOG.debug("X509TrustManager.checkServerTrusted {} {}",
                                chain, authType);
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        LOG.debug("X509TrustManager.getAcceptedIssuers");
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
            sslContext = SSLContext.getInstance(SSL_CONTEXT_PROTOCOL);
            sslContext.init(keyManagers, trustManagers, null);
        } catch (final Exception e) {
            LOG.error("Failed to initialize the server-side SSLContext", e);
        }
    }

    /**
     * Generates an 1024 bit RSA key pair using SHA1PRNG. Thoughts: 2048 takes
     * much longer time on older CPUs. And for almost every client, 1024 is
     * sufficient.
     * 
     * Derived from Zed Attack Proxy (ZAP). ZAP is an HTTP/HTTPS proxy for
     * assessing web application security. Copyright 2011 mawoki@ymail.com
     * Licensed under the Apache License, Version 2.0
     * 
     * @see org.parosproxy.paros.security.SslCertificateServiceImpl.
     *      createCertForHost(String)
     * @see org.parosproxy.paros.network.SSLConnector.getTunnelSSLSocketFactory(
     *      String)
     */
    public SSLEngine createCertForHost(String commonName,
            Collection<List<?>> subjectAlternativeNames)
            throws NoSuchAlgorithmException, InvalidKeyException,
            CertificateException, NoSuchProviderException, SignatureException,
            KeyStoreException, IOException, UnrecoverableKeyException,
            KeyManagementException, OperatorCreationException {
        MillisecondsDuration duration = new MillisecondsDuration();

        if (commonName == null) {
            throw new IllegalArgumentException(
                    "Error, 'commonName' is not allowed to be null!");
        }
        if (subjectAlternativeNames == null) {
            throw new IllegalArgumentException(
                    "Error, 'subjectAlternativeNames' is not allowed to be null!");
        }

        final SSLContext cached = (SSLContext) serverSSLContexts
                .get(commonName);
        if (cached != null) {
            SSLEngine result = cached.createSSLEngine();
            LOG.debug("Use certificate for {} in {}ms", commonName, duration);
            return result;
        }

        final KeyPair mykp = createKeyPair(FAKE_KEYSIZE);
        final PrivateKey privKey = mykp.getPrivate();
        final PublicKey pubKey = mykp.getPublic();

        X500NameBuilder namebld = new X500NameBuilder(BCStyle.INSTANCE);
        namebld.addRDN(BCStyle.CN, commonName);
        namebld.addRDN(BCStyle.O, "Mo Cuishle");
        namebld.addRDN(BCStyle.OU, "Offline Cache");

        X500Name subject = new X509CertificateHolder(caCert.getEncoded())
                .getSubject();
        BigInteger serial = BigInteger.valueOf(serverCertificateSerial
                .getAndIncrement());
        Date notBefore = new Date(System.currentTimeMillis() - 1000L * 60 * 60
                * 24 * 30);
        Date notAfter = new Date(System.currentTimeMillis() + 100
                * (1000L * 60 * 60 * 24 * 30));
        X500Name name = namebld.build();
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, name, pubKey);

        certGen.addExtension(X509Extension.subjectKeyIdentifier, false,
                new SubjectKeyIdentifierStructure(pubKey));
        certGen.addExtension(X509Extension.basicConstraints, false,
                new BasicConstraints(false));

        if (!subjectAlternativeNames.isEmpty()) {
            ASN1Encodable[] encodables = new ASN1Encodable[subjectAlternativeNames
                    .size()];
            Iterator<List<?>> it = subjectAlternativeNames.iterator();
            for (int i = 0; i < encodables.length; i++) {
                final List<?> each = it.next();
                final String subjectAlternativeName = String.valueOf(each
                        .get(1));
                final int tag = Integer.parseInt(String.valueOf(each.get(0)));
                encodables[i] = new GeneralName(tag, subjectAlternativeName);
            }
            GeneralNames seq = new GeneralNames(new DERSequence(encodables));
            certGen.addExtension(X509Extension.subjectAlternativeName, false,
                    seq);
        }

        final ContentSigner sigGen = new JcaContentSignerBuilder(
                SIGNATURE_ALGORITHM).setProvider("BC").build(caPrivKey);
        final X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(certGen.build(sigGen));

        // XXX Is it really necessary to check this here?
        //
        cert.checkValidity(new Date());
        cert.verify(caCert.getPublicKey());

        // XXX Is it really necessary to build a key store here?
        //
        final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        final Certificate[] chain = new Certificate[2];
        chain[1] = this.caCert;
        chain[0] = cert;
        ks.setKeyEntry(BouncyCastleSslEngineSource.ALIAS, privKey,
                BouncyCastleSslEngineSource.PASSWORD, chain);

        // ------------------- from getTunnelSSLSocketFactory ------------------

        SSLContext ctx = SSLContext.getInstance(SSL_CONTEXT_PROTOCOL);
        String algorithm = KeyManagerFactory.getDefaultAlgorithm();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);

        kmf.init(ks, PASSWORD);
        java.security.SecureRandom x = new java.security.SecureRandom();
        x.setSeed(System.currentTimeMillis());
        ctx.init(kmf.getKeyManagers(), null, x);
        if (serverSSLContexts.containsKey(commonName)) {
            LOG.debug("Duplicate generated cert ignored for cache.");
        } else {
            serverSSLContexts.put(commonName, ctx);
        }
        SSLEngine result = ctx.createSSLEngine();

        LOG.info("Impersonated {} in {}ms", commonName, duration);
        return result;
    }

    private KeyPair createKeyPair(int keysize) throws NoSuchAlgorithmException {
        final KeyPairGenerator keyGen = KeyPairGenerator
                .getInstance(KEY_ALGORITHM);
        final SecureRandom random = SecureRandom
                .getInstance(SECURE_RANDOM_ALGORITHM);
        random.setSeed(Long.toString(System.currentTimeMillis()).getBytes());
        keyGen.initialize(keysize, random);
        final KeyPair keypair = keyGen.generateKeyPair();
        return keypair;
    }
}

class MillisecondsDuration {
    private final long mStartTime = System.currentTimeMillis();

    @Override
    public String toString() {
        return String.valueOf(System.currentTimeMillis() - mStartTime);
    }
}
