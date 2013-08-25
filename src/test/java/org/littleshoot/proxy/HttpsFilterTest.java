package org.littleshoot.proxy;

import static org.junit.Assert.*;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.LittleProxyConfig;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.littleshoot.proxy.impl.SelfSignedKeyStoreManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpsFilterTest {

    private static final int PROXY_PORT = 8923;
    private static final int WEB_SERVER_PORT = 8924;
    private static final int WEB_SERVER_SSL_PORT = 8443;

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private AtomicInteger shouldFilterCalls = new AtomicInteger(0);
    private AtomicInteger filterCalls = new AtomicInteger(0);
    private Queue<HttpRequest> associatedRequests = new LinkedList<HttpRequest>();
    private HttpProxyServer proxyServer;
    private Server webServer;

    @Before
    public void setUp() throws Exception {
        shouldFilterCalls = new AtomicInteger(0);
        filterCalls = new AtomicInteger(0);
        associatedRequests = new LinkedList<HttpRequest>();
        
        final HttpFilter filter = new HttpFilter() {

            public boolean filterResponses(final HttpRequest httpRequest) {
                shouldFilterCalls.incrementAndGet();
                return true;
            }

            public int getMaxResponseSize() {
                return 1024 * 1024;
            }

            public void filterResponse(final HttpRequest httpRequest, final HttpResponse response) {
                filterCalls.incrementAndGet();
                if (httpRequest != null) {
                    associatedRequests.add(httpRequest);
                } else {
                    log.error("REQUEST IS NULL!!");
                }
            }
        };

        final HttpResponseFilters responseFilters =
                new HttpResponseFilters() {
                    public HttpFilter getFilter(final String hostAndPort) {
                        System.out.println(hostAndPort);
                        if (hostAndPort.equals("localhost:" + WEB_SERVER_PORT) || hostAndPort.equals("localhost:" + WEB_SERVER_SSL_PORT)) {
                            return filter;
                        }
                        return null;
                    }
                };

        LittleProxyConfig.setUseMITMInSSL(true);
        LittleProxyConfig.setAcceptAllSSLCertificates(true);
        proxyServer =
                new DefaultHttpProxyServer(PROXY_PORT, responseFilters, null, null, null);
        proxyServer.start();
        
        webServer = new Server(WEB_SERVER_PORT);
        org.eclipse.jetty.util.ssl.SslContextFactory sslContextFactory = new org.eclipse.jetty.util.ssl.SslContextFactory();

        org.littleshoot.proxy.impl.SslContextFactory scf = new org.littleshoot.proxy.impl.SslContextFactory(new SelfSignedKeyStoreManager());
        SSLContext sslContext = scf.getServerContext();

        sslContextFactory.setSslContext(sslContext);
        SslSocketConnector connector = new SslSocketConnector(sslContextFactory);
        connector.setPort(WEB_SERVER_SSL_PORT);
        webServer.addConnector(connector);
        webServer.start();
    }
    
    @After
    public void tearDown() throws Exception {
        try {
            webServer.stop();
        } finally {
            proxyServer.stop();
        }
    }
    
    @Test public void testHttpsFiltering() throws Exception {

        final String url1 = "http://localhost:"+WEB_SERVER_PORT+"/testing";
        final String url2 = "https://localhost:"+WEB_SERVER_SSL_PORT+"/testing";

        final InetSocketAddress isa = new InetSocketAddress("127.0.0.1", PROXY_PORT);
        while (true) {
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
        // stripping host since in this run the proxy is not transparent. see ProxyHttpRequestEncoder
        assertEquals(ProxyUtils.stripHost(url2), second.getUri());

    }

    private HttpEntity getResponse(final String url) throws Exception {
        final DefaultHttpClient http = new DefaultHttpClient();

        SSLSocketFactory sf = new SSLSocketFactory(new TrustSelfSignedStrategy());
        sf.setHostnameVerifier(new X509HostnameVerifier() {
            public boolean verify(String arg0, SSLSession arg1) {
                return true;
            }
            public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {
            }
            public void verify(String host, X509Certificate cert) throws SSLException {
            }
            public void verify(String host, SSLSocket ssl) throws IOException {
            }
        });
        Scheme scheme = new Scheme("https", 443, sf);
        http.getConnectionManager().getSchemeRegistry().register(scheme);

        final HttpHost proxy = new HttpHost("127.0.0.1", PROXY_PORT, "http");
        http.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        http.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 30000);
        //http.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 30000);

        final HttpGet get = new HttpGet(url);
        final org.apache.http.HttpResponse hr = http.execute(get);
        final HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        http.getConnectionManager().shutdown();
        return responseEntity;
    }
}
