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
import static org.junit.Assert.assertThat;
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
    public void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @After
    public void tearDown() {
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
        // the proxy should remove all Connection headers, since all values in the Connection header are hop-by-hop headers.
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/connectionheaders"),
                Times.exactly(1))
                .respond(response()
                        .withStatusCode(200)
                        .withBody("success")
                        .withHeader("Connection", "Dummy-Header")
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
    }
}
