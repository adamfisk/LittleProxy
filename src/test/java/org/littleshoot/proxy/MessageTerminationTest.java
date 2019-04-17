package org.littleshoot.proxy;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ConnectionOptions;

import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.Assert.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class MessageTerminationTest {
    private ClientAndServer mockServer;
    private int mockServerPort;
    private HttpProxyServer proxyServer;

    @Before
    public void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
        }

        if (proxyServer != null) {
            proxyServer.abort();
        }
    }

    @Test
    public void testResponseWithoutTerminationIsChunked() throws Exception {
        // set up the server so that it indicates the end of the response by closing the connection. the proxy
        // should automatically add the Transfer-Encoding: chunked header when sending to the client.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(true)
                                        .withSuppressConnectionHeader(true)
                                        .withSuppressContentLengthHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

        assertEquals("Expected to receive a 200 from the server", 200, response.getStatusLine().getStatusCode());

        // verify the Transfer-Encoding header was added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat("Expected to see a Transfer-Encoding header", transferEncodingHeaders.length, greaterThanOrEqualTo(1));
        String transferEncoding = transferEncodingHeaders[0].getValue();
        assertEquals("Expected Transfer-Encoding to be chunked", "chunked", transferEncoding);

        String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
        response.getEntity().getContent().close();

        assertEquals("Success!", bodyString);
    }

    @Test
    public void testResponseWithContentLengthNotModified() throws Exception {
        // the proxy should not modify the response since it contains a Content-Length header.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(true)
                                        .withSuppressConnectionHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

        assertEquals("Expected to receive a 200 from the server", 200, response.getStatusLine().getStatusCode());

        // verify the Transfer-Encoding header was NOT added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat("Did not expect to see a Transfer-Encoding header", transferEncodingHeaders, emptyArray());

        String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
        response.getEntity().getContent().close();

        assertEquals("Success!", bodyString);
    }

    @Test
    public void testFilterAddsContentLength() throws Exception {
        // when a filter with buffering is added to the filter chain, the aggregated FullHttpResponse should
        // automatically have a Content-Length header
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(true)
                                        .withSuppressConnectionHeader(true)
                                        .withSuppressContentLengthHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public int getMaximumResponseBufferSizeInBytes() {
                        return 100000;
                    }
                })
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();


        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpGet("http://127.0.0.1:" + mockServerPort + "/"));

        assertEquals("Expected to receive a 200 from the server", 200, response.getStatusLine().getStatusCode());

        // verify the Transfer-Encoding header was NOT added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat("Did not expect to see a Transfer-Encoding header", transferEncodingHeaders, emptyArray());

        Header[] contentLengthHeaders = response.getHeaders("Content-Length");
        assertThat("Expected to see a Content-Length header", contentLengthHeaders.length, greaterThanOrEqualTo(1));

        String bodyString = EntityUtils.toString(response.getEntity(), "ISO-8859-1");
        response.getEntity().getContent().close();

        assertEquals("Success!", bodyString);
    }

    @Test
    public void testResponseToHEADNotModified() throws Exception {
        // the proxy should not modify the response since it is an HTTP HEAD request
        mockServer.when(request()
                        .withMethod("HEAD")
                        .withPath("/"),
                Times.unlimited())
                .respond(response()
                                .withStatusCode(200)
                                .withConnectionOptions(new ConnectionOptions()
                                        .withCloseSocket(false)
                                        .withSuppressConnectionHeader(true)
                                        .withSuppressContentLengthHeader(true))
                );

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        int proxyServerPort = proxyServer.getListenAddress().getPort();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServerPort);
        HttpResponse response = httpClient.execute(new HttpHead("http://127.0.0.1:" + mockServerPort + "/"));

        assertEquals("Expected to receive a 200 from the server", 200, response.getStatusLine().getStatusCode());

        // verify the Transfer-Encoding header was NOT added
        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat("Did not expect to see a Transfer-Encoding header", transferEncodingHeaders, emptyArray());

        // verify the Content-Length header was not added
        Header[] contentLengthHeaders = response.getHeaders("Content-Length");
        assertThat("Did not expect to see a Content-Length header", contentLengthHeaders, emptyArray());

        assertNull("Expected response to HEAD to have no entity body", response.getEntity());
    }
}
