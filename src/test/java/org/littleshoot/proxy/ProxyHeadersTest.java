package org.littleshoot.proxy;

import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.ConnectionOptions;

import static org.hamcrest.Matchers.emptyArray;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests the proxy's handling and manipulation of headers.
 */
public class ProxyHeadersTest {
    private HttpProxyServer proxyServer;

    private ClientAndServer mockServer;
    private int mockServerPort;

    @Before
    public void setUp() throws Exception {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getPort();
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        } finally {
            if (mockServer != null) {
                mockServer.stop();
            }
        }
    }

    @Test
    public void testProxyRemovesConnectionHeadersFromServer() throws Exception {
        // the proxy should remove all Connection headers, since it is a hop-by-hop header. however, since the proxy does not
        // generally modify the Transfer-Encoding of the message, it should not remove the Transfer-Encoding header.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/connectionheaders"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success")
                        .withHeader("Connection", "Transfer-Encoding, Dummy-Header")
                        .withHeader("Transfer-Encoding", "identity")
                        .withHeader("Dummy-Header", "dummy-value")
                        .withConnectionOptions(new ConnectionOptions()
                                .withSuppressConnectionHeader(true))
                );

        this.proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        HttpClient httpClient = TestUtils.createProxiedHttpClient(proxyServer.getListenAddress().getPort());
        HttpResponse response = httpClient.execute(new HttpGet("http://localhost:" + mockServerPort + "/connectionheaders"));
        EntityUtils.consume(response.getEntity());

        Header[] dummyHeaders = response.getHeaders("Dummy-Header");
        assertThat("Expected proxy to remove the Dummy-Header specified in the Connection header", dummyHeaders, emptyArray());

        Header[] transferEncodingHeaders = response.getHeaders("Transfer-Encoding");
        assertThat("Expected proxy to keep the Transfer-Encoding header, even when specified in the Connection header", transferEncodingHeaders, not(emptyArray()));

        // make sure we find the "identity" header, which should not be removed
        boolean foundIdentity = false;
        for (Header transferEncodingHeader : transferEncodingHeaders) {
            if ("identity".equals(transferEncodingHeader.getValue())) {
                foundIdentity = true;
                break;
            }
        }

        assertTrue("Expected to find Transfer-Encoding: identity header value specified in response", foundIdentity);
    }
}
