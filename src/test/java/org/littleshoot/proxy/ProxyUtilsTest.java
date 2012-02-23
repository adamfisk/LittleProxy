package org.littleshoot.proxy;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Test for proxy utilities.
 */
public class ProxyUtilsTest {

    @Test
    public void testParseHost() throws Exception {
        String full = "http://www.test.com";
        assertEquals("www.test.com", ProxyUtils.parseHost(full));
        
        full = "http://www.test.com:80/test";
        assertEquals("www.test.com", ProxyUtils.parseHost(full));
        
        full = "https://www.test.com:80/test";
        assertEquals("www.test.com", ProxyUtils.parseHost(full));
        
        full = "www.test.com:80/test";
        assertEquals("www.test.com", ProxyUtils.parseHost(full));
        
        full = "www.test.com";
        assertEquals("www.test.com", ProxyUtils.parseHost(full));
    }
}
