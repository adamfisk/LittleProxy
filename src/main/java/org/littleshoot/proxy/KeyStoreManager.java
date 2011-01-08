package org.littleshoot.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

import javax.net.ssl.TrustManager;

public interface KeyStoreManager {

    void addBase64Cert(URI uri, String base64Cert) throws IOException;
    
    String getBase64Cert();
    
    InputStream keyStoreAsInputStream();
    
    char[] getCertificatePassword();
    
    char[] getKeyStorePassword();

    TrustManager[] getTrustManagers();

    InputStream trustStoreAsInputStream();

    void reset(String jid);

}
