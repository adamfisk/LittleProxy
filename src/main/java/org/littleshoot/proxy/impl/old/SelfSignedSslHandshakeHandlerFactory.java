package org.littleshoot.proxy.impl.old;



class SelfSignedSslHandshakeHandlerFactory 
    extends SslHandshakeHandlerFactory {

    public SelfSignedSslHandshakeHandlerFactory() {
        super(new SelfSignedKeyStoreManager());
    }
}
