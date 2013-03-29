package org.littleshoot.proxy;


public class SelfSignedSslHandshakeHandlerFactory 
    extends SslHandshakeHandlerFactory {

    public SelfSignedSslHandshakeHandlerFactory() {
        super(new SelfSignedKeyStoreManager());
    }
}
