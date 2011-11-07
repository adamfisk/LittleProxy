package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class HttpFilterTest {
    
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Test public void testFiltering() throws Exception {
        final int port = 8923;
        final Map<String, HttpFilter> responseFilters = 
            new HashMap<String, HttpFilter>();
        
        final AtomicInteger shouldFilterCalls = new AtomicInteger(0);
        final AtomicInteger filterCalls = new AtomicInteger(0);
        final Queue<HttpRequest> associatedRequests = new LinkedList<HttpRequest>();
        
        final String url1 = "http://www.google.com";
        final String url2 = "http://www.google.com/testing";
        final HttpFilter filter = new HttpFilter() {
            
            public boolean shouldFilterResponses(final HttpRequest httpRequest) {
                shouldFilterCalls.incrementAndGet();
                return true;
            }
            
            public int getMaxResponseSize() {
                return 1024 * 1024;
            }
            
            public HttpResponse filterResponse(final HttpRequest httpRequest,
                final HttpResponse response) {
                filterCalls.incrementAndGet();
                if (httpRequest != null) {
                    associatedRequests.add(httpRequest);
                } else {
                    log.error("REQUEST IS NULL!!");
                }
                return response;
            }
        };
        responseFilters.put("www.google.com", filter);
        final HttpProxyServer server = 
            new DefaultHttpProxyServer(port, responseFilters);
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
            Thread.sleep(50);
        }
        
        final DefaultHttpClient http = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", port);
        http.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        final HttpGet get = new HttpGet(url1);
        org.apache.http.HttpResponse hr = http.execute(get);
        HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);

        assertEquals(1, associatedRequests.size());
        assertEquals(1, shouldFilterCalls.get());
        assertEquals(1, filterCalls.get());
        
        // We just open a second connection here since reusing the original 
        // connection is inconsistent.
        final HttpGet get2 = new HttpGet(url2);
        hr = http.execute(get2);
        responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        
        assertEquals(2, shouldFilterCalls.get());
        assertEquals(2, filterCalls.get());
        assertEquals(2, associatedRequests.size());
        
        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        
        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertEquals(url1, first.getUri());
        assertEquals(url2, second.getUri());
        http.getConnectionManager().shutdown();
    }
}
