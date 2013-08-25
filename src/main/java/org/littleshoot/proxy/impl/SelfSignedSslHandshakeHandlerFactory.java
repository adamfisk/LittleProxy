package org.littleshoot.proxy.impl;


public class SelfSignedSslHandshakeHandlerFactory 
    extends SslHandshakeHandlerFactory {

    public SelfSignedSslHandshakeHandlerFactory() {
        super(new SelfSignedKeyStoreManager());
    }
}
