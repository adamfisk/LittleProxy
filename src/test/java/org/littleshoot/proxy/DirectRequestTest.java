package org.littleshoot.proxy;

import static org.junit.Assert.*;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.io.IOException;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * This class tests direct requests to the proxy server, which causes endless
 * loops (#205).
 */
public class DirectRequestTest {

    private HttpProxyServer proxyServer;

    @Before
    public void setUp() throws Exception {
        proxyServer = null;
    }

    @After
    public void tearDown() throws Exception {
        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    @Test(timeout = 5000)
    public void testAnswerBadRequestInsteadOfEndlessLoop() throws Exception {

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest);
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()//
                .withPort(0)//
                .withFiltersSource(filtersSource)//
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = getResponse("http://127.0.0.1:" + proxyPort + "/directToProxy");
        int statusCode = response.getStatusLine().getStatusCode();

        assertEquals("Expected to receive an HTTP 400 from the server", 400, statusCode);
    }

    @Test(timeout = 5000)
    public void testAnswerFromFilterShouldBeServed() throws Exception {

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(403));
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()//
                .withPort(0)//
                .withFiltersSource(filtersSource)//
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();
        org.apache.http.HttpResponse response = getResponse("http://localhost:" + proxyPort + "/directToProxy");
        int statusCode = response.getStatusLine().getStatusCode();

        assertEquals("Expected to receive an HTTP 403 from the server", 403, statusCode);
    }

    @Test(timeout = 5000, expected = SSLException.class)
    public void testHttpsShouldCancelConnection() throws Exception {

        HttpFiltersSource filtersSource = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                        return new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(403));
                    }
                };
            }
        };

        proxyServer = DefaultHttpProxyServer.bootstrap()//
                .withPort(0)//
                .withFiltersSource(filtersSource)//
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();
        getResponse("https://localhost:" + proxyPort + "/directToProxy");
    }

    private DefaultHttpClient buildHttpClient() throws Exception {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        SSLSocketFactory sf = new SSLSocketFactory(new TrustSelfSignedStrategy(), new X509HostnameVerifier() {
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
        httpClient.getConnectionManager().getSchemeRegistry().register(scheme);
        return httpClient;
    }

    private org.apache.http.HttpResponse getResponse(final String url) throws Exception {
        final DefaultHttpClient http = buildHttpClient();

        final HttpGet get = new HttpGet(url);

        return getHttpResponse(http, get);
    }

    private org.apache.http.HttpResponse getHttpResponse(DefaultHttpClient httpClient, HttpUriRequest get)
            throws IOException {
        final org.apache.http.HttpResponse hr = httpClient.execute(get);
        final HttpEntity responseEntity = hr.getEntity();
        EntityUtils.consume(responseEntity);
        httpClient.getConnectionManager().shutdown();
        return hr;
    }

}
