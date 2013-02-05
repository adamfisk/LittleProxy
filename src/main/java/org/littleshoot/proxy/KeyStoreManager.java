package org.littleshoot.proxy;

import java.io.InputStream;

public interface KeyStoreManager {

    InputStream keyStoreAsInputStream();
    
    char[] getCertificatePassword();
    
    char[] getKeyStorePassword();

    String getBase64Cert(String id);

}
