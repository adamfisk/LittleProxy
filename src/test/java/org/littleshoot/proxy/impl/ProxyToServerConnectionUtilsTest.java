package org.littleshoot.proxy.impl;

import org.junit.Test;
import org.littleshoot.proxy.HostResolver;

import java.net.UnknownHostException;

import static org.mockito.Mockito.*;

/**
 * Unit tests for static helper methods in {@link ProxyToServerConnection}.
 */
public class ProxyToServerConnectionUtilsTest {
    @Test
    public void testParseAddresses() throws UnknownHostException {
        // mock out the proxy server and resolver; this test only verifies the addresses parse correctly
        DefaultHttpProxyServer mockProxyServer = mock(DefaultHttpProxyServer.class);
        HostResolver mockHostResolver = mock(HostResolver.class);

        when(mockProxyServer.getServerResolver()).thenReturn(mockHostResolver);

        ProxyToServerConnection.addressFor("192.168.1.1", mockProxyServer);
        verify(mockHostResolver).resolve("192.168.1.1", 80);

        ProxyToServerConnection.addressFor("192.168.1.1:72", mockProxyServer);
        verify(mockHostResolver).resolve("192.168.1.1", 72);

        ProxyToServerConnection.addressFor("www.google.com", mockProxyServer);
        verify(mockHostResolver).resolve("www.google.com", 80);

        ProxyToServerConnection.addressFor("www.google.com:19650", mockProxyServer);
        verify(mockHostResolver).resolve("www.google.com", 19650);

        ProxyToServerConnection.addressFor("[::1]", mockProxyServer);
        verify(mockHostResolver).resolve("::1", 80);

        ProxyToServerConnection.addressFor("[::1]:56500", mockProxyServer);
        verify(mockHostResolver).resolve("::1", 56500);

        ProxyToServerConnection.addressFor("[a:b:c:d::1]", mockProxyServer);
        verify(mockHostResolver).resolve("a:b:c:d::1", 80);

        ProxyToServerConnection.addressFor("[a:b:c:d::1]:8650", mockProxyServer);
        verify(mockHostResolver).resolve("a:b:c:d::1", 8650);
    }
}
