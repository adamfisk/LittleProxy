package org.littleshoot.proxy.extras;

import java.io.ByteArrayInputStream;
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
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
import org.bouncycastle.asn1.ASN1EncodableVector;
import org.bouncycastle.asn1.ASN1InputStream;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectKeyIdentifier;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.bc.BcX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

/**
 * A {@link SslEngineSource} which creates a key store with a Root Certificate
 * Authority. The certificates are generated lazily if the given key store file
 * doesn't yet exist.
 * 
 * The root certificate is exported in PEM format to be used in a browser. The
 * proxy application presents for every host a dynamically created certificate
 * to the browser, signed by this certificate authority.
 * 
 * This facilitates the proxy to handle as a "Man In The Middle" to filter the
 * decrypted content in clear text.
 * 
 * The hard part was done by mawoki. It's derived from Zed Attack Proxy (ZAP).
 * ZAP is an HTTP/HTTPS proxy for assessing web application security. Copyright
 * 2011 mawoki@ymail.com Licensed under the Apache License, Version 2.0
 */
public class BouncyCastleSslEngineSource implements SslEngineSource {

    private static final Logger LOG = LoggerFactory
            .getLogger(BouncyCastleSslEngineSource.class);

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

    private static final String KEY_STORE_FILE_EXTENSION = ".p12";

    /**
     * Root CA has to be installed in browsers. Hundred years should avoid
     * expiration.
     */
    private static final int VALIDITY = 36500;

    private final Authority authority;

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

    private Cache<String, SSLContext> serverSSLContexts;

    /**
     * Creates a SSL engine source create a Certificate Authority if needed and
     * initializes a SSL context. Exceptions will be thrown to let the manager
     * decide how to react. Don't install a MITM manager in the proxy in case of
     * a failure.
     * 
     * @param authority
     *            a parameter object to provide personal informations of the
     *            Certificate Authority and the dynamic certificates.
     * 
     * @param trustAllServers
     * 
     * @param sendCerts
     * 
     * @param sslContexts
     *            a cache to store dynamically created server certificates.
     *            Generation takes between 50 to 500ms, but only once per
     *            thread, since there is a connection cache too. It's save to
     *            give a null cache to prevent memory or locking issues.
     * 
     * @throws IOException
     * @throws RootCertificateException
     * @throws KeyStoreException
     * @throws CertificateException
     * @throws OperatorCreationException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws KeyManagementException
     * @throws UnrecoverableKeyException
     */
    public BouncyCastleSslEngineSource(Authority authority,
            boolean trustAllServers, boolean sendCerts,
            Cache<String, SSLContext> sslContexts) throws InvalidKeyException,
            NoSuchAlgorithmException, OperatorCreationException,
            CertificateException, KeyStoreException, RootCertificateException,
            IOException, UnrecoverableKeyException, KeyManagementException {
        this.authority = authority;
        this.trustAllServers = trustAllServers;
        this.sendCerts = sendCerts;
        this.serverCertificateSerial = initRandomSerial();
        this.serverSSLContexts = sslContexts;
        Security.addProvider(new BouncyCastleProvider());
        initializeKeyStore();
        initializeSSLContext();
    }

    /**
     * Creates a SSL engine source create a Certificate Authority if needed and
     * initializes a SSL context. This constructor defaults a cache to store
     * dynamically created server certificates. Exceptions will be thrown to let
     * the manager decide how to react. Don't install a MITM manager in the
     * proxy in case of a failure.
     * 
     * @param authority
     *            a parameter object to provide personal informations of the
     *            Certificate Authority and the dynamic certificates.
     * 
     * @param trustAllServers
     * 
     * @param sendCerts
     * 
     * @throws IOException
     * @throws RootCertificateException
     * @throws KeyStoreException
     * @throws CertificateException
     * @throws OperatorCreationException
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws KeyManagementException
     * @throws UnrecoverableKeyException
     */
    public BouncyCastleSslEngineSource(Authority authority,
            boolean trustAllServers, boolean sendCerts)
            throws RootCertificateException, UnrecoverableKeyException,
            KeyManagementException, KeyStoreException,
            NoSuchAlgorithmException, CertificateException, IOException,
            InvalidKeyException, OperatorCreationException {
        this(authority, trustAllServers, sendCerts,
                initDefaultCertificateCache());
    }

    private static Cache<String, SSLContext> initDefaultCertificateCache() {
        return CacheBuilder.newBuilder() //
                .expireAfterAccess(5, TimeUnit.MINUTES) //
                .concurrencyLevel(16) //
                .build();
    }

    private AtomicLong initRandomSerial() {
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

    private void initializeKeyStore() throws RootCertificateException,
            InvalidKeyException, NoSuchAlgorithmException,
            OperatorCreationException, CertificateException, KeyStoreException,
            IOException {
        if (authority.aliasFile(KEY_STORE_FILE_EXTENSION).exists()) {
            return;
        }
        MillisecondsDuration duration = new MillisecondsDuration();
        KeyStore keystore = createRootCA();
        LOG.info("Created root certificate authority key store in {}ms",
                duration);

        OutputStream os = null;
        try {
            os = new FileOutputStream(
                    authority.aliasFile(KEY_STORE_FILE_EXTENSION));
            keystore.store(os, authority.password());
        } finally {
            IOUtils.closeQuietly(os);
        }

        final Certificate cert = keystore.getCertificate(authority.alias());
        Writer sw = null;
        JcaPEMWriter pw = null;
        try {
            sw = new FileWriter(authority.aliasFile(".pem"));
            pw = new JcaPEMWriter(sw);
            pw.writeObject(cert);
            pw.flush();
        } finally {
            IOUtils.closeQuietly(pw);
            IOUtils.closeQuietly(sw);
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
     * @throws NoSuchAlgorithmException
     * @throws InvalidKeyException
     * @throws OperatorCreationException
     * @throws CertificateException
     * @throws KeyStoreException
     * @throws IOException
     * 
     * @see org.zaproxy.zap.extension.dynssl.SslCertificateUtils.createRootCA()
     */
    public KeyStore createRootCA() throws NoSuchAlgorithmException,
            InvalidKeyException, OperatorCreationException,
            CertificateException, KeyStoreException, IOException {
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
        namebld.addRDN(BCStyle.CN, authority.commonName());
        namebld.addRDN(BCStyle.O, authority.organization());
        namebld.addRDN(BCStyle.OU, authority.organizationalUnitName());

        Random rnd = new Random();
        X509v3CertificateBuilder certGen = new JcaX509v3CertificateBuilder(
                namebld.build(), BigInteger.valueOf(rnd.nextInt()), startDate,
                expireDate, namebld.build(), pubKey);

        certGen.addExtension(Extension.subjectKeyIdentifier, false,
                createSubjectKeyIdentifier(pubKey));
        certGen.addExtension(Extension.basicConstraints, true,
                new BasicConstraints(true));
        certGen.addExtension(Extension.keyUsage, false, new KeyUsage(
                KeyUsage.keyCertSign | KeyUsage.digitalSignature
                        | KeyUsage.keyEncipherment | KeyUsage.dataEncipherment
                        | KeyUsage.cRLSign));

        ASN1EncodableVector eku = new ASN1EncodableVector();
        eku.add(KeyPurposeId.id_kp_serverAuth);
        eku.add(KeyPurposeId.id_kp_clientAuth);
        eku.add(KeyPurposeId.anyExtendedKeyUsage);
        DERSequence seq = new DERSequence(eku);
        certGen.addExtension(Extension.extendedKeyUsage, false, seq);

        final ContentSigner sigGen = new JcaContentSignerBuilder(
                SIGNATURE_ALGORITHM).setProvider("BC").build(privKey);
        final X509Certificate cert = new JcaX509CertificateConverter()
                .setProvider("BC").getCertificate(certGen.build(sigGen));

        final KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
        ks.load(null, null);
        ks.setKeyEntry(authority.alias(), privKey, authority.password(),
                new Certificate[] { cert });
        return ks;
    }

    private SubjectKeyIdentifier createSubjectKeyIdentifier(PublicKey pub)
            throws IOException {
        ByteArrayInputStream bIn = new ByteArrayInputStream(pub.getEncoded());
        ASN1InputStream is = null;
        try {
            is = new ASN1InputStream(bIn);
            SubjectPublicKeyInfo info = new SubjectPublicKeyInfo(
                    (ASN1Sequence) is.readObject());
            return new BcX509ExtensionUtils().createSubjectKeyIdentifier(info);
        } finally {
            IOUtils.closeQuietly(is);
        }
    }

    private void initializeSSLContext() throws KeyStoreException,
            NoSuchAlgorithmException, CertificateException, IOException,
            UnrecoverableKeyException, KeyManagementException {
        final KeyStore ks = KeyStore.getInstance(KEY_STORE_TYPE);
        FileInputStream is = null;
        try {
            is = new FileInputStream(
                    authority.aliasFile(KEY_STORE_FILE_EXTENSION));
            ks.load(is, authority.password());
        } finally {
            IOUtils.closeQuietly(is);
        }
        this.caCert = ks.getCertificate(authority.alias());
        this.caPrivKey = (PrivateKey) ks.getKey(authority.alias(),
                authority.password());

        // Set up key manager factory to use our key store
        String keyManAlg = KeyManagerFactory.getDefaultAlgorithm();
        final KeyManagerFactory kmf = KeyManagerFactory.getInstance(keyManAlg);
        kmf.init(ks, authority.password());

        // Set up a trust manager factory to use our key store
        String trustManAlg = TrustManagerFactory.getDefaultAlgorithm();
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(trustManAlg);
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
     * @param commonName
     *            the common name to use in the server certificate
     * 
     * @param subjectAlternativeNames
     *            a List of the subject alternative names to use in the server
     *            certificate, could be empty, but must not be null
     * 
     * @throws IOException
     * @throws KeyStoreException
     * @throws SignatureException
     * @throws NoSuchProviderException
     * @throws CertificateException
     * @throws OperatorCreationException
     * @throws NoSuchAlgorithmException
     * @throws KeyManagementException
     * @throws UnrecoverableKeyException
     * @throws CertificateNotYetValidException
     * @throws CertificateExpiredException
     * @throws InvalidKeyException
     * @throws CertificateEncodingException
     * @throws ExecutionException
     * 
     * @see org.parosproxy.paros.security.SslCertificateServiceImpl.
     *      createCertForHost(String)
     * @see org.parosproxy.paros.network.SSLConnector.getTunnelSSLSocketFactory(
     *      String)
     */
    public SSLEngine createCertForHost(final String commonName,
            final Collection<List<?>> subjectAlternativeNames)
            throws CertificateEncodingException, InvalidKeyException,
            CertificateExpiredException, CertificateNotYetValidException,
            UnrecoverableKeyException, KeyManagementException,
            NoSuchAlgorithmException, OperatorCreationException,
            CertificateException, NoSuchProviderException, SignatureException,
            KeyStoreException, IOException, ExecutionException {

        if (commonName == null) {
            throw new IllegalArgumentException(
                    "Error, 'commonName' is not allowed to be null!");
        }
        if (subjectAlternativeNames == null) {
            throw new IllegalArgumentException(
                    "Error, 'subjectAlternativeNames' is not allowed to be null!");
        }

        SSLContext ctx;
        if (serverSSLContexts == null) {
            ctx = createServerContext(commonName, subjectAlternativeNames);
        } else {
            ctx = serverSSLContexts.get(commonName, new Callable<SSLContext>() {
                @Override
                public SSLContext call() throws Exception {
                    return createServerContext(commonName,
                            subjectAlternativeNames);
                }
            });
        }
        return ctx.createSSLEngine();
    }

    private SSLContext createServerContext(String commonName,
            Collection<List<?>> subjectAlternativeNames)
            throws NoSuchAlgorithmException, IOException,
            CertificateEncodingException, InvalidKeyException,
            OperatorCreationException, CertificateException,
            CertificateExpiredException, CertificateNotYetValidException,
            NoSuchProviderException, SignatureException, KeyStoreException,
            UnrecoverableKeyException, KeyManagementException {

        final MillisecondsDuration duration = new MillisecondsDuration();

        final KeyPair mykp = createKeyPair(FAKE_KEYSIZE);
        final PrivateKey privKey = mykp.getPrivate();
        final PublicKey pubKey = mykp.getPublic();

        X500NameBuilder namebld = new X500NameBuilder(BCStyle.INSTANCE);
        namebld.addRDN(BCStyle.CN, commonName);
        namebld.addRDN(BCStyle.O, authority.certOrganisation());
        namebld.addRDN(BCStyle.OU, authority.certOrganizationalUnitName());

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

        certGen.addExtension(Extension.subjectKeyIdentifier, false,
                createSubjectKeyIdentifier(pubKey));
        certGen.addExtension(Extension.basicConstraints, false,
                new BasicConstraints(false));

        if (!subjectAlternativeNames.isEmpty()) {
            ASN1EncodableVector eku = new ASN1EncodableVector();
            Iterator<List<?>> it = subjectAlternativeNames.iterator();
            while (it.hasNext()) {
                final List<?> each = it.next();
                final String subjectAlternativeName = String.valueOf(each
                        .get(1));
                final int tag = Integer.parseInt(String.valueOf(each.get(0)));
                eku.add(new GeneralName(tag, subjectAlternativeName));
            }
            DERSequence seq = new DERSequence(eku);
            certGen.addExtension(Extension.subjectAlternativeName, false, seq);
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
        ks.setKeyEntry(authority.alias(), privKey, authority.password(), chain);

        // ------------------- from getTunnelSSLSocketFactory ------------------

        SSLContext ctx = SSLContext.getInstance(SSL_CONTEXT_PROTOCOL);
        String algorithm = KeyManagerFactory.getDefaultAlgorithm();
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);

        kmf.init(ks, authority.password());
        SecureRandom random = new SecureRandom();
        random.setSeed(System.currentTimeMillis());
        ctx.init(kmf.getKeyManagers(), null, random);
        LOG.info("Impersonated {} in {}ms", commonName, duration);
        return ctx;
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
