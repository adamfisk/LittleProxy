package org.littleshoot.proxy.impl;

import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

import java.util.List;

import static org.hamcrest.Matchers.hasSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * Test for proxy utilities.
 */
public class ProxyUtilsTest {

    @Test
    public void testParseHostAndPort() throws Exception {
        assertEquals("www.test.com:80", ProxyUtils.parseHostAndPort("http://www.test.com:80/test"));
        assertEquals("www.test.com:80", ProxyUtils.parseHostAndPort("https://www.test.com:80/test"));
        assertEquals("www.test.com:443", ProxyUtils.parseHostAndPort("https://www.test.com:443/test"));
        assertEquals("www.test.com:80", ProxyUtils.parseHostAndPort("www.test.com:80/test"));
        assertEquals("www.test.com", ProxyUtils.parseHostAndPort("http://www.test.com"));
        assertEquals("www.test.com", ProxyUtils.parseHostAndPort("www.test.com"));
        assertEquals("httpbin.org:443", ProxyUtils.parseHostAndPort("httpbin.org:443/get"));
    }

    @Test
    public void testAddNewViaHeader() {
        String hostname = ProxyUtils.getHostName();

        HttpMessage httpMessage = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/endpoint");
        ProxyUtils.addVia(httpMessage);

        List<String> viaHeaders = httpMessage.headers().getAll(HttpHeaders.Names.VIA);
        assertThat(viaHeaders, hasSize(1));

        String expectedViaHeader = "1.1 " + hostname;
        assertEquals(expectedViaHeader, viaHeaders.get(0));
    }

    @Test
    public void testAddNewViaHeaderToExistingViaHeader() {
        String hostname = ProxyUtils.getHostName();

        HttpMessage httpMessage = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/endpoint");
        httpMessage.headers().add(HttpHeaders.Names.VIA, "1.1 otherproxy");
        ProxyUtils.addVia(httpMessage);

        List<String> viaHeaders = httpMessage.headers().getAll(HttpHeaders.Names.VIA);
        assertThat(viaHeaders, hasSize(2));

        assertEquals("1.1 otherproxy", viaHeaders.get(0));

        String expectedViaHeader = "1.1 " + hostname;
        assertEquals(expectedViaHeader, viaHeaders.get(1));
    }

}
