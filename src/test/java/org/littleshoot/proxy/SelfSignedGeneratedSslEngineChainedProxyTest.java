package org.littleshoot.proxy;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;

import javax.net.ssl.SSLEngine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

public class SelfSignedGeneratedSslEngineChainedProxyTest extends BaseChainedProxyTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private SslEngineSource sslEngineSource;

    @Override
    protected void setUp() throws IOException {
        String keyStorePath = temporaryFolder.newFolder("certs").toPath().resolve("chain_proxy_keystore.jks")
                .toString();
        sslEngineSource = new SelfSignedSslEngineSource(keyStorePath, false, true, "littleproxy",
                "Be Your Own Lantern");
        super.setUp();
    }

    @Test
    public void testKeyStoreGeneratedAtProvidedPath() {
        File keyStoreFile = temporaryFolder.getRoot().toPath().resolve("./certs/chain_proxy_keystore.jks").toFile();
        assertTrue(keyStoreFile.exists());
    }

    @Override
    protected HttpProxyServerBootstrap upstreamProxy() {
        return super.upstreamProxy()
                .withSslEngineSource(sslEngineSource);
    }

    @Override
    protected ChainedProxy newChainedProxy() {
        return new BaseChainedProxy() {
            @Override
            public boolean requiresEncryption() {
                return true;
            }

            @Override
            public SSLEngine newSslEngine() {
                return sslEngineSource.newSslEngine();
            }
        };
    }
}
