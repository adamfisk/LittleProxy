package org.littleshoot.proxy.extras;

import io.netty.handler.codec.http.HttpRequest;
import org.junit.Test;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.*;

public class SelfSignedMitmManagerTest {

    @Test
    public void testServerSslEnginePeerAndPort() {
        String peer = "localhost";
        int port = 8090;
        SelfSignedSslEngineSource source = mock(SelfSignedSslEngineSource.class);
        SelfSignedMitmManager manager = new SelfSignedMitmManager(source);
        SSLEngine engine = mock(SSLEngine.class);
        when(source.newSslEngine(peer, port)).thenReturn(engine);
        assertEquals(engine, manager.serverSslEngine(peer, port));
    }

    @Test
    public void testServerSslEngine() {
        SelfSignedSslEngineSource source = mock(SelfSignedSslEngineSource.class);
        SelfSignedMitmManager manager = new SelfSignedMitmManager(source);
        SSLEngine engine = mock(SSLEngine.class);
        when(source.newSslEngine()).thenReturn(engine);
        assertEquals(engine, manager.serverSslEngine());
    }

    @Test
    public void testClientSslEngineFor() {
        HttpRequest request = mock(HttpRequest.class);
        SSLSession session = mock(SSLSession.class);
        SelfSignedSslEngineSource source = mock(SelfSignedSslEngineSource.class);
        SelfSignedMitmManager manager = new SelfSignedMitmManager(source);
        SSLEngine engine = mock(SSLEngine.class);
        when(source.newSslEngine()).thenReturn(engine);
        assertEquals(engine, manager.clientSslEngineFor(request, session));
        verifyZeroInteractions(request, session);
    }
}
