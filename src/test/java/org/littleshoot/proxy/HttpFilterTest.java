package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.junit.Test;


public class HttpFilterTest {

    @Test public void testFiltering() throws Exception {
        final int port = 8923;
        final Map<String, HttpFilter> responseFilters = 
            new HashMap<String, HttpFilter>();
        
        final AtomicInteger shouldFilterCalls = new AtomicInteger(0);
        final AtomicInteger filterCalls = new AtomicInteger(0);
        final HttpFilter filter = new HttpFilter() {
            
            public boolean shouldFilterResponses(final HttpRequest httpRequest) {
                shouldFilterCalls.incrementAndGet();
                return true;
            }
            
            public int getMaxResponseSize() {
                return 1024 * 1024;
            }
            
            public HttpResponse filterResponse(final HttpResponse response) {
                filterCalls.incrementAndGet();
                return response;
            }
        };
        responseFilters.put("www.google.com", filter);
        final HttpProxyServer server = 
            new DefaultHttpProxyServer(port, responseFilters);
        System.out.println("About to start...");
        server.start();
        boolean connected = false;
        final InetSocketAddress isa = new InetSocketAddress("127.0.0.1", port);
        while (!connected) {
            final Socket sock = new Socket();
            try {
                sock.connect(isa);
                break;
            } catch (final IOException e) {
                // Keep trying.
            } finally {
                IOUtils.closeQuietly(sock);
            }
            Thread.sleep(100);
        }
        
        final DefaultHttpClient http = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", port);
        http.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        final String url = "http://www.google.com";
        HttpGet get = new HttpGet(url);
        org.apache.http.HttpResponse hr = http.execute(get);
        HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        
        assertEquals(1, shouldFilterCalls.get());
        assertEquals(1, filterCalls.get());
        get = new HttpGet("http://www.google.com/testing");
        
        hr = http.execute(get);
        responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        
        assertEquals(1, shouldFilterCalls.get());
        assertEquals(2, filterCalls.get());
        http.getConnectionManager().shutdown();
    }
}
