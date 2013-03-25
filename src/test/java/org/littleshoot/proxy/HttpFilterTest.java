package org.littleshoot.proxy;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class HttpFilterTest {

    private static final int PROXY_PORT = 8923;
    private static final int WEB_SERVER_PORT = 8924;

    private final Logger log = LoggerFactory.getLogger(getClass());

    @Test public void testFiltering() throws Exception {

        final AtomicInteger shouldFilterCalls = new AtomicInteger(0);
        final AtomicInteger filterCalls = new AtomicInteger(0);
        final Queue<HttpRequest> associatedRequests = 
            new LinkedList<HttpRequest>();

        final String url1 = "http://localhost:8924/";
        final String url2 = "http://localhost:8924/testing";
        final HttpFilter filter = new HttpFilter() {

            public boolean filterResponses(final HttpRequest httpRequest) {
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
        final HttpResponseFilters responseFilters = 
            new HttpResponseFilters() {
                public HttpFilter getFilter(final String hostAndPort) {
                    if (hostAndPort.equals("localhost:8924")) {
                        return filter;
                    }
                    return null;
                }
            };
        final HttpProxyServer server =
            new DefaultHttpProxyServer(PROXY_PORT, responseFilters);
        server.start();
        boolean connected = false;
        final InetSocketAddress isa = new InetSocketAddress("127.0.0.1", PROXY_PORT);
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

        final Server webServer = new Server(WEB_SERVER_PORT);
        webServer.start();

        getResponse(url1);

        assertEquals(1, associatedRequests.size());
        assertEquals(1, shouldFilterCalls.get());
        assertEquals(1, filterCalls.get());
        
        // We just open a second connection here since reusing the original 
        // connection is inconsistent.
        getResponse(url2);

        
        assertEquals(2, shouldFilterCalls.get());
        assertEquals(2, filterCalls.get());
        assertEquals(2, associatedRequests.size());
        
        final HttpRequest first = associatedRequests.remove();
        final HttpRequest second = associatedRequests.remove();
        
        // Make sure the requests in the filter calls were the requests they
        // actually should have been.
        assertEquals(url1, first.getUri());
        assertEquals(url2, second.getUri());

        webServer.stop();
        server.stop();
    }

    private HttpEntity getResponse(final String url) throws Exception {
        final DefaultHttpClient http = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", PROXY_PORT, "http");
        http.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        final HttpGet get = new HttpGet(url);
        final org.apache.http.HttpResponse hr = http.execute(get);
        final HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        http.getConnectionManager().shutdown();
        return responseEntity;
    }

}
