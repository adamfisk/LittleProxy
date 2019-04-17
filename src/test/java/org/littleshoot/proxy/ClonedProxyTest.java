package org.littleshoot.proxy;

import org.apache.http.HttpResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.test.HttpClientUtil;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;

import static org.junit.Assert.assertEquals;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class ClonedProxyTest {
    private ClientAndServer mockServer;
    private int mockServerPort;

    private HttpProxyServer originalProxy;
    private HttpProxyServer clonedProxy;

    @Before
    public void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @After
    public void tearDown() {
        try {
            if (mockServer != null) {
                mockServer.stop();
            }
        } finally {
            try {
                if (originalProxy != null) {
                    originalProxy.abort();
                }
            } finally {
                if (clonedProxy != null) {
                    clonedProxy.abort();
                }
            }
        }
    }

    @Test
    public void testClonedProxyHandlesRequests() {
        originalProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withName("original")
                .start();
        clonedProxy = originalProxy.clone()
                .withName("clone")
                .start();

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/testClonedProxyHandlesRequests"),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withBody("success")
                );

        HttpResponse response = HttpClientUtil.performHttpGet("http://localhost:" + mockServerPort + "/testClonedProxyHandlesRequests", clonedProxy);
        assertEquals("Expected to receive a 200 when making a request using the cloned proxy server", 200, response.getStatusLine().getStatusCode());
    }

    @Test
    public void testStopClonedProxyDoesNotStopOriginalServer() {
        originalProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withName("original")
                .start();
        clonedProxy = originalProxy.clone()
                .withName("clone")
                .start();

        clonedProxy.abort();

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/testClonedProxyHandlesRequests"),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withBody("success")
                );

        HttpResponse response = HttpClientUtil.performHttpGet("http://localhost:" + mockServerPort + "/testClonedProxyHandlesRequests", originalProxy);
        assertEquals("Expected to receive a 200 when making a request using the cloned proxy server", 200, response.getStatusLine().getStatusCode());
    }

    @Test
    public void testStopOriginalServerDoesNotStopClonedServer() {
        originalProxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withName("original")
                .start();
        clonedProxy = originalProxy.clone()
                .withName("clone")
                .start();

        originalProxy.abort();

        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/testClonedProxyHandlesRequests"),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withBody("success")
                );

        HttpResponse response = HttpClientUtil.performHttpGet("http://localhost:" + mockServerPort + "/testClonedProxyHandlesRequests", clonedProxy);
        assertEquals("Expected to receive a 200 when making a request using the cloned proxy server", 200, response.getStatusLine().getStatusCode());
    }
}
