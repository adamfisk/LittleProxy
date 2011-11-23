package org.littleshoot.proxy;

import static org.junit.Assert.*;

import java.util.concurrent.atomic.AtomicInteger;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.junit.Test;


public class RegexHttpRequestFilterTest {
    
    @Test public void testHost() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        final HttpRequestFilter delegate = new HttpRequestFilter() {
            
            public void filter(final HttpRequest httpRequest) {
                synchronized (count) {
                    count.incrementAndGet();
                    count.notifyAll();
                }
            }
        };
        final RegexHttpRequestFilter filter = 
            RegexHttpRequestFilter.newHostFilter(".*google.com", delegate);
        testHostRegex(filter, 2, count);
    }
    
    @Test public void testPath() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        final HttpRequestFilter delegate = new HttpRequestFilter() {
            
            public void filter(final HttpRequest httpRequest) {
                synchronized (count) {
                    count.incrementAndGet();
                    count.notifyAll();
                }
            }
        };
        RegexHttpRequestFilter filter = 
            RegexHttpRequestFilter.newPathFilter("/testing", delegate);
        testHostRegex(filter, 1, count);
        
        count.set(0);
        filter = RegexHttpRequestFilter.newPathFilter("/test.*", delegate);
        testHostRegex(filter, 2, count);
    }
    
    @Test public void testHostAndPath() throws Exception {
        final AtomicInteger count = new AtomicInteger();
        final HttpRequestFilter delegate = new HttpRequestFilter() {
            
            public void filter(final HttpRequest httpRequest) {
                synchronized (count) {
                    count.incrementAndGet();
                    count.notifyAll();
                }
            }
        };
        final RegexHttpRequestFilter filter = 
            RegexHttpRequestFilter.newHostAndPathFilter(".*.google.com", 
                "/testing", delegate);
        testHostRegex(filter, 1, count);
    }
    
    private void testHostRegex(final RegexHttpRequestFilter filter, 
        final int expected, final AtomicInteger count) throws Exception {

        
        final int port = 7777;
        final DefaultHttpProxyServer server = 
            new DefaultHttpProxyServer(port, filter);
        server.start();
        
        final String url1 = "http://www.google.com/testing";
        final String url2 = "http://www.google.com/test";
        
        final DefaultHttpClient http = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", port);
        http.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        HttpGet get = new HttpGet(url1);
        HttpResponse hr = http.execute(get);
        HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        
        get = new HttpGet(url2);
        hr = http.execute(get);
        responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);

        // Only one of the requests should have 
        assertEquals(expected, count.get());
        server.stop();
    }
}
