package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;
import static org.littleshoot.proxy.ProxyUtils.parseHost;
import static org.littleshoot.proxy.ProxyUtils.parseHostAndPort;

import java.net.InetSocketAddress;

import org.junit.Test;

/**
 * Test for proxy utilities.
 */
public class ProxyUtilsTest {

    @Test
    public void testParseHost() throws Exception {
        assertEquals("www.test.com", parseHost("http://www.test.com"));
        assertEquals("www.test.com", parseHost("http://www.test.com:80/test"));
        assertEquals("www.test.com", parseHost("https://www.test.com:80/test"));
        assertEquals("www.test.com", parseHost("www.test.com:80/test"));
        assertEquals("www.test.com", parseHost("www.test.com"));
    }

    @Test
    public void testParseHostAndPort() throws Exception {
        assertEquals(new InetSocketAddress("www.test.com", 80), parseHostAndPort("http://www.test.com:80/test"));
        assertEquals(new InetSocketAddress("www.test.com", 80), parseHostAndPort("https://www.test.com:80/test"));
        assertEquals(new InetSocketAddress("www.test.com", 80), parseHostAndPort("www.test.com:80/test"));
        assertEquals(new InetSocketAddress("www.test.com", 80), parseHostAndPort("http://www.test.com"));
        assertEquals(new InetSocketAddress("www.test.com", 80), parseHostAndPort("www.test.com"));
    }
}
