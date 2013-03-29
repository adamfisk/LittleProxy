package org.littleshoot.proxy;

import javax.net.ssl.SSLEngine;

import org.jboss.netty.handler.ssl.SslHandler;

public class SslHandshakeHandlerFactory implements HandshakeHandlerFactory {

    private final KeyStoreManager ksm;
    
    public SslHandshakeHandlerFactory(final KeyStoreManager ksm) {
        this.ksm = ksm;
    }
        
    @Override
    public HandshakeHandler newHandshakeHandler() {
        final SslContextFactory scf = new SslContextFactory(ksm);
        final SSLEngine engine = scf.getServerContext().createSSLEngine();
        engine.setUseClientMode(false);
        return new SslHandshakeHandler("ssl", new SslHandler(engine));
    }
}
