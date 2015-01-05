package org.littleshoot.proxy.extras;

import iaik.asn1.structures.AlgorithmID;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.math.BigInteger;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.security.cert.X509Certificate;

import org.apache.commons.io.IOUtils;
//import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.littleshoot.proxy.SslEngineSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sun.security.x509.AlgorithmId;
import sun.security.x509.CertificateAlgorithmId;
import sun.security.x509.CertificateExtensions;
import sun.security.x509.CertificateIssuerName;
import sun.security.x509.CertificateSerialNumber;
import sun.security.x509.CertificateSubjectName;
import sun.security.x509.CertificateVersion;
import sun.security.x509.CertificateX509Key;
import sun.security.x509.Extension;
import sun.security.x509.KeyIdentifier;
import sun.security.x509.SubjectAlternativeNameExtension;
import sun.security.x509.SubjectKeyIdentifierExtension;
import sun.security.x509.X500Name;
import sun.security.x509.X509CertImpl;
import sun.security.x509.X509CertInfo;

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
    private static final String KEY_STORE_TYPE = "jks";
    private static final String SIG_ALG_NAME = "SHA256withRSA";
    
    
    private final File keyStoreFile;
    private final boolean trustAllServers;
    private final boolean sendCerts;

    private SSLContext sslContext;

	private KeyStore keyStore;
	
	private ConcurrentHashMap<String, SoftReference<SSLContext>> sslContextCache = new ConcurrentHashMap<>();

	
//	// Add the BouncyCastle security provider if not available yet
//	static {
//		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
//			Security.addProvider(new BouncyCastleProvider());
//		}
//	}
	   
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
    
    public SSLEngine clientSslEngineFor(SSLSession remoteServerSslSession) {
    	try{

    		String sslContextKey = remoteServerSslSession.getPeerHost() +":" + remoteServerSslSession.getPeerPort();
    		SSLContext newSslContext = null;
    		if(!sslContextCache.contains(sslContextKey) || (newSslContext = sslContextCache.get(sslContextKey).get()) == null ){
    			
    			X509Certificate[] remoteServerCertChain = remoteServerSslSession.getPeerCertificateChain();
        		X509CertImpl remoteServerCert = new X509CertImpl(remoteServerCertChain[0].getEncoded());
        		X509CertInfo remoteServerCertInfo = (X509CertInfo) remoteServerCert.get(X509CertImpl.NAME + "." + X509CertImpl.INFO);

        		newSslContext = SSLContext.getInstance("SSL");
        		// Get our key pair and our own DN (not the remote server's DN) from the keystore.
        		X509CertImpl caCert = new X509CertImpl(keyStore.getCertificate(ALIAS).getEncoded());
        		PrivateKey caPrivateKey = (PrivateKey) keyStore.getKey(ALIAS, PASSWORD.toCharArray());
        		PublicKey caPublicKey = caCert.getPublicKey();

        		X509CertInfo caCertInfo = (X509CertInfo) caCert.get(X509CertImpl.NAME + "." + X509CertImpl.INFO);
        		X500Name issuer = (X500Name) caCertInfo.get(X509CertInfo.SUBJECT + "." + CertificateIssuerName.DN_NAME);

        		//modify certificats
        		Random random = new Random();
        		remoteServerCertInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        		remoteServerCertInfo.set(X509CertInfo.ISSUER + "." + CertificateSubjectName.DN_NAME, issuer);
        		remoteServerCertInfo.set(X509CertInfo.KEY, new CertificateX509Key(caPublicKey));
        		remoteServerCertInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(random.nextLong()+"")));
        		
        		

        		X509CertImpl newCert = new X509CertImpl(remoteServerCertInfo);
        		// The inner and outer signature algorithms have to match.
        		// The way we achieve that is really ugly, but there seems to be no
        		// other solution: We first sign the cert, then retrieve the
        		// outer sigalg and use it to set the inner sigalg
        		newCert.sign(caPrivateKey, SIG_ALG_NAME);
        		AlgorithmId sigAlgid = (AlgorithmId)newCert.get(X509CertImpl.SIG_ALG);
        		remoteServerCertInfo.set(CertificateAlgorithmId.NAME + "." + CertificateAlgorithmId.ALGORITHM, sigAlgid);

        		CertificateExtensions extensions = (CertificateExtensions)remoteServerCertInfo.get(X509CertInfo.EXTENSIONS);
        		Enumeration<Extension> elements = extensions.getElements();
        		CertificateExtensions newExtensions = new CertificateExtensions();
        		while(elements!=null && elements.hasMoreElements()){
        			Extension ext = elements.nextElement();
        			if(ext instanceof SubjectAlternativeNameExtension){
        				newExtensions.set(SubjectAlternativeNameExtension.NAME, ext);
        			}
        		}

        		//newExtensions.set(SubjectKeyIdentifierExtension.NAME, new SubjectKeyIdentifierExtension(new KeyIdentifier(caPublicKey).getIdentifier()));
        		remoteServerCertInfo.set(X509CertInfo.EXTENSIONS, newExtensions);

        		// Sign the new certificate
        		newCert = new X509CertImpl(remoteServerCertInfo);
        		newCert.sign(caPrivateKey, SIG_ALG_NAME);


        		KeyStore serverKeyStore = KeyStore.getInstance(KEY_STORE_TYPE);
        		serverKeyStore.load(null, PASSWORD.toCharArray());

        		serverKeyStore.setCertificateEntry(ALIAS, newCert);
        		serverKeyStore.setKeyEntry(ALIAS, caPrivateKey, PASSWORD.toCharArray(), new Certificate[] { newCert });

        		final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        		keyManagerFactory.init(serverKeyStore, PASSWORD.toCharArray());

        		newSslContext.init(keyManagerFactory.getKeyManagers(), new TrustManager[] { new TrustEveryone() }, null);
        		
        		sslContextCache.put(sslContextKey, new SoftReference<SSLContext>(newSslContext));
    		}
    		
    		return newSslContext.createSSLEngine();
            
    	}catch(Exception e){
    		throw new RuntimeException("newSslEngine", e);
    	}
    }
    
    
    public SSLEngine _newSslEngine(SSLSession remoteServerSslSession) {
    	try{
    		
            X509Certificate[] remoteServerCertChain = remoteServerSslSession.getPeerCertificateChain();
            iaik.x509.X509Certificate remoteServerCertificate = new iaik.x509.X509Certificate(remoteServerCertChain[0].getEncoded());
            //Principal remoteServerDN = remoteServerCertificate.getSubjectDN();
            //BigInteger remoteServerSerialNumber = remoteServerCertificate.getSerialNumber();

    	 	// You may find it useful to work from the comment skeleton below.
            SSLContext dynamicSslContext = SSLContext.getInstance("SSL");
            final char[] keyStorePassword = PASSWORD.toCharArray();
            final String keyStoreType = KEY_STORE_TYPE;
            String alias = ALIAS;

            // Get our key pair and our own DN (not the remote server's DN) from the keystore.
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(alias, keyStorePassword);
            iaik.x509.X509Certificate certificate = new iaik.x509.X509Certificate(keyStore.getCertificate(alias).getEncoded());
            PublicKey publicKey = (PublicKey) certificate.getPublicKey();
            Principal caSubjectDN = certificate.getSubjectDN();
			
			remoteServerCertificate.setPublicKey(publicKey);
			remoteServerCertificate.setIssuerDN(caSubjectDN);

			 /*
			Collection<List<?>> remoteSANList = remoteServerCertificate.getSubjectAlternativeNames();
            if(remoteSANList!=null){
            	 GeneralNames generalNames = new GeneralNames();
                 Iterator<List<?>> iter = remoteSANList.iterator();
                 while (iter.hasNext()) {
                 	List<?> next =  iter.next();
                 	int OID = ((Integer) next.get(0)).intValue();
                 	GeneralName generalName = new GeneralName(OID, next.get(1));
                 	generalNames.addName(generalName);
                 }
                 SubjectAltName sanExt = new SubjectAltName();
                 sanExt.setGeneralNames(generalNames);
                 serverCertificate.addExtension(sanExt);
            }
			   
           
            Enumeration enumaration = remoteServerCertificate.listExtensions();
            while(enumaration!=null &&  enumaration.hasMoreElements()){
            	serverCertificate.addExtension((V3Extension) enumaration.nextElement());
            }
             */
          
            if(privateKey.getAlgorithm().equals("DSA"))
                remoteServerCertificate.sign(AlgorithmID.dsaWithSHA1, privateKey);
            else if(privateKey.getAlgorithm().equals("RSA"))
                remoteServerCertificate.sign(AlgorithmID.sha1WithRSAEncryption, privateKey);
            else
                throw new RuntimeException("Unrecognized Signing Method!");


            KeyStore serverKeyStore = KeyStore.getInstance(keyStoreType);
            serverKeyStore.load(null, keyStorePassword);

            serverKeyStore.setCertificateEntry(alias, remoteServerCertificate);
            serverKeyStore.setKeyEntry(alias, privateKey, keyStorePassword, new Certificate[] { remoteServerCertificate });
            
            final KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagerFactory.init(serverKeyStore, keyStorePassword);

            dynamicSslContext.init(keyManagerFactory.getKeyManagers(), new TrustManager[] { new TrustEveryone() }, null);
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
        String algorithm = Security.getProperty("ssl.KeyManagerFactory.algorithm");
        if (algorithm == null) {
            algorithm = "SunX509";
        }

        try {
            this.keyStore = KeyStore.getInstance("JKS");
            // ks.load(new FileInputStream("keystore.jks"),
            // "changeit".toCharArray());
            keyStore.load(new FileInputStream(keyStoreFile), PASSWORD.toCharArray());

            // Set up key manager factory to use our key store
            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(algorithm);
            kmf.init(keyStore, PASSWORD.toCharArray());

            // Set up a trust manager factory to use our key store
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(algorithm);
            tmf.init(keyStore);

            TrustManager[] trustManagers = null;
            if (!trustAllServers) {
                trustManagers = tmf.getTrustManagers();
            } else {
                trustManagers = new TrustManager[] {new TrustEveryone()};
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
            throw new Error("Failed to initialize the server-side SSLContext", e);
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
	 * We're carrying out a MITM attack, we don't care whether the cert chains
	 * are trusted or not ;-)
	 * 
	 */
	private static class TrustEveryone implements javax.net.ssl.X509TrustManager {
		public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authenticationType) {
		}

		public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authenticationType) {
		}

		public java.security.cert.X509Certificate[] getAcceptedIssuers() {
			return null;
		}
	}

}
