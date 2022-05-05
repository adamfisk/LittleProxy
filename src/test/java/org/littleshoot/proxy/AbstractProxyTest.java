package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import javax.net.ssl.SSLSession;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

/**
 * Base for tests that test the proxy. This base class encapsulates all of the
 * testing infrastructure.
 */
public abstract class AbstractProxyTest {
    protected static final String DEFAULT_RESOURCE = "/";

    protected int webServerPort = -1;
    protected int httpsWebServerPort = -1;

    protected HttpHost webHost;
    protected HttpHost httpsWebHost;

    /**
     * The server used by the tests.
     */
    protected HttpProxyServer proxyServer;

    /**
     * Holds the most recent response after executing a test method.
     */
    protected String lastResponse;

    /**
     * The web server that provides the back-end.
     */
    private Server webServer;

    protected AtomicInteger bytesReceivedFromClient;
    protected AtomicInteger requestsReceivedFromClient;
    protected AtomicInteger bytesSentToServer;
    protected AtomicInteger requestsSentToServer;
    protected AtomicInteger bytesReceivedFromServer;
    protected AtomicInteger responsesReceivedFromServer;
    protected AtomicInteger bytesSentToClient;
    protected AtomicInteger responsesSentToClient;
    protected AtomicInteger clientConnects;
    protected AtomicInteger clientSSLHandshakeSuccesses;
    protected AtomicInteger clientDisconnects;

    @Before
    public void initializeCounters() {
        bytesReceivedFromClient = new AtomicInteger(0);
        requestsReceivedFromClient = new AtomicInteger(0);
        bytesSentToServer = new AtomicInteger(0);
        requestsSentToServer = new AtomicInteger(0);
        bytesReceivedFromServer = new AtomicInteger(0);
        responsesReceivedFromServer = new AtomicInteger(0);
        bytesSentToClient = new AtomicInteger(0);
        responsesSentToClient = new AtomicInteger(0);
        clientConnects = new AtomicInteger(0);
        clientSSLHandshakeSuccesses = new AtomicInteger(0);
        clientDisconnects = new AtomicInteger(0);
    }

    @Before
    public void runSetUp() throws Exception {
        webServer = TestUtils.startWebServer(true);

        // find out what ports the HTTP and HTTPS connectors were bound to
        httpsWebServerPort = TestUtils.findLocalHttpsPort(webServer);
        if (httpsWebServerPort < 0) {
            throw new RuntimeException("HTTPS connector should already be open and listening, but port was " + webServerPort);
        }

        webServerPort = TestUtils.findLocalHttpPort(webServer);
        if (webServerPort < 0) {
            throw new RuntimeException("HTTP connector should already be open and listening, but port was " + webServerPort);
        }

        webHost = new HttpHost("127.0.0.1", webServerPort);
        httpsWebHost = new HttpHost("127.0.0.1", httpsWebServerPort, "https");

        setUp();
    }

    protected abstract void setUp() throws Exception;

    @After
    public void runTearDown() throws Exception {
        try {
            tearDown();
        } finally {
            try {
                if (this.proxyServer != null) {
                    this.proxyServer.abort();
                }
            } finally {
                if (this.webServer != null) {
                    webServer.stop();
                }
            }
        }
    }

    protected void tearDown() throws Exception {
    }

    /**
     * Override this to specify a username to use when authenticating with
     * proxy.
     */
    protected String getUsername() {
        return null;
    }

    /**
     * Override this to specify a password to use when authenticating with
     * proxy.
     */
    protected String getPassword() {
        return null;
    }

    protected void assertReceivedBadGateway(ResponseInfo response) {
        assertEquals("Received: " + response, 502, response.getStatusCode());
    }

    protected ResponseInfo httpPostWithApacheClient(
            HttpHost host, String resourceUrl, boolean isProxied)
            throws Exception {
        final boolean supportSsl = true;
        String username = getUsername();
        String password = getPassword();
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(
                isProxied, supportSsl, proxyServer.getListenAddress().getPort(), username, password)) {
            final HttpPost request = new HttpPost(resourceUrl);
            request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);

            final StringEntity entity = new StringEntity("adsf", "UTF-8");
            entity.setChunked(true);
            request.setEntity(entity);

            final HttpResponse response = httpClient.execute(host, request);
            final HttpEntity resEntity = response.getEntity();
            return new ResponseInfo(response.getStatusLine().getStatusCode(),
                    EntityUtils.toString(resEntity));
        }
    }

    protected ResponseInfo httpGetWithApacheClient(HttpHost host,
            String resourceUrl, boolean isProxied, boolean callHeadFirst)
            throws Exception {
        final boolean supportSsl = true;
        String username = getUsername();
        String password = getPassword();
        try (CloseableHttpClient httpClient = TestUtils.buildHttpClient(
                isProxied, supportSsl, proxyServer.getListenAddress().getPort(), username, password)){
            Integer contentLength = null;
            if (callHeadFirst) {
                HttpHead request = new HttpHead(resourceUrl);
                request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);
                HttpResponse response = httpClient.execute(host, request);
                contentLength = new Integer(response.getFirstHeader(
                        "Content-Length").getValue());
            }

            HttpGet request = new HttpGet(resourceUrl);
            request.setConfig(TestUtils.REQUEST_TIMEOUT_CONFIG);

            HttpResponse response = httpClient.execute(host, request);
            HttpEntity resEntity = response.getEntity();

            if (contentLength != null) {
                assertEquals(
                        "Content-Length from GET should match that from HEAD",
                        contentLength,
                        new Integer(response.getFirstHeader("Content-Length")
                                .getValue()));
            }
            return new ResponseInfo(response.getStatusLine().getStatusCode(),
                    EntityUtils.toString(resEntity));
        }
    }

    protected String compareProxiedAndUnproxiedPOST(HttpHost host,
            String resourceUrl) throws Exception {
        ResponseInfo proxiedResponse = httpPostWithApacheClient(host,
                resourceUrl, true);
        if (expectBadGatewayForEverything()) {
            assertReceivedBadGateway(proxiedResponse);
        } else {
            ResponseInfo unproxiedResponse = httpPostWithApacheClient(host,
                    resourceUrl, false);
            assertEquals(unproxiedResponse, proxiedResponse);
            checkStatistics(host);
        }
        return proxiedResponse.getBody();
    }

    protected String compareProxiedAndUnproxiedGET(HttpHost host,
            String resourceUrl) throws Exception {
        ResponseInfo proxiedResponse = httpGetWithApacheClient(host,
                resourceUrl, true, false);
        if (expectBadGatewayForEverything()) {
            assertReceivedBadGateway(proxiedResponse);
        } else {
            ResponseInfo unproxiedResponse = httpGetWithApacheClient(host,
                    resourceUrl, false, false);
            assertEquals(unproxiedResponse, proxiedResponse);
            checkStatistics(host);
        }
        return proxiedResponse.getBody();
    }

    private void checkStatistics(HttpHost host) {
        boolean isHTTPS = host.getSchemeName().equalsIgnoreCase("HTTPS");
        int numberOfExpectedClientInteractions = 1;
        int numberOfExpectedServerInteractions = 1;
        if (isAuthenticating()) {
            numberOfExpectedClientInteractions += 1;
        }
        if (isHTTPS && isMITM()) {
            numberOfExpectedClientInteractions += 1;
            numberOfExpectedServerInteractions += 1;
        }
        if (isHTTPS && !isChained()) {
            numberOfExpectedServerInteractions -= 1;
        }
        assertThat(bytesReceivedFromClient.get(), greaterThan(0));
        assertEquals(numberOfExpectedClientInteractions,
                requestsReceivedFromClient.get());
        assertThat(bytesSentToServer.get(), greaterThan(0));
        assertEquals(numberOfExpectedServerInteractions,
                requestsSentToServer.get());
        assertThat(bytesReceivedFromServer.get(), greaterThan(0));
        assertEquals(numberOfExpectedServerInteractions,
                responsesReceivedFromServer.get());
        assertThat(bytesSentToClient.get(), greaterThan(0));
        assertEquals(numberOfExpectedClientInteractions,
                responsesSentToClient.get());
    }

    /**
     * Override this to indicate that the proxy is chained.
     */
    protected boolean isChained() {
        return false;
    }

    /**
     * Override this to indicate that the test uses authentication.
     */
    protected boolean isAuthenticating() {
        return false;
    }

    protected boolean isMITM() {
        return false;
    }

    protected boolean expectBadGatewayForEverything() {
        return false;
    }

    protected HttpProxyServerBootstrap bootstrapProxy() {
        return DefaultHttpProxyServer.bootstrap().plusActivityTracker(
                new ActivityTracker() {
                    @Override
                    public void bytesReceivedFromClient(
                            FlowContext flowContext,
                            int numberOfBytes) {
                        bytesReceivedFromClient.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void requestReceivedFromClient(
                            FlowContext flowContext,
                            HttpRequest httpRequest) {
                        requestsReceivedFromClient.incrementAndGet();
                    }

                    @Override
                    public void bytesSentToServer(FullFlowContext flowContext,
                            int numberOfBytes) {
                        bytesSentToServer.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void requestSentToServer(
                            FullFlowContext flowContext,
                            HttpRequest httpRequest) {
                        requestsSentToServer.incrementAndGet();
                    }

                    @Override
                    public void bytesReceivedFromServer(
                            FullFlowContext flowContext,
                            int numberOfBytes) {
                        bytesReceivedFromServer.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void responseReceivedFromServer(
                            FullFlowContext flowContext,
                            io.netty.handler.codec.http.HttpResponse httpResponse) {
                        responsesReceivedFromServer.incrementAndGet();
                    }

                    @Override
                    public void bytesSentToClient(FlowContext flowContext,
                            int numberOfBytes) {
                        bytesSentToClient.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void responseSentToClient(
                            FlowContext flowContext,
                            io.netty.handler.codec.http.HttpResponse httpResponse) {
                        responsesSentToClient.incrementAndGet();
                    }

                    @Override
                    public void clientConnected(InetSocketAddress clientAddress) {
                        clientConnects.incrementAndGet();
                    }

                    @Override
                    public void clientSSLHandshakeSucceeded(
                            InetSocketAddress clientAddress,
                            SSLSession sslSession) {
                        clientSSLHandshakeSuccesses.incrementAndGet();
                    }

                    @Override
                    public void clientDisconnected(
                            InetSocketAddress clientAddress,
                            SSLSession sslSession) {
                        clientDisconnects.incrementAndGet();
                    }
                });
    }
}
