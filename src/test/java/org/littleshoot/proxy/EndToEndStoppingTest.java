package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
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
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.greaterThan;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End to end test making sure the proxy is able to service simple HTTP requests
 * and stop at the end. Made into a unit test from isopov and nasis's
 * contributions at: https://github.com/adamfisk/LittleProxy/issues/36
 */
public class EndToEndStoppingTest {
    private static final Logger log = LoggerFactory.getLogger(EndToEndStoppingTest.class);

    private ClientAndServer mockServer;
    private int mockServerPort;

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
    }

    /**
     * This is a quick test from nasis that exhibits different behavior from
     * unit tests because unit tests call System.exit(). The stop method should
     * stop all non-daemon threads and should cause the JVM to exit without
     * explicitly calling System.exit(), which running as an application
     * properly tests.
     */
    public static void main(final String[] args) {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        Proxy proxy = new Proxy();
        proxy.setProxyType(Proxy.ProxyType.MANUAL);
        String proxyStr = String.format("localhost:%d", proxyServer.getListenAddress().getPort());
        proxy.setHttpProxy(proxyStr);
        proxy.setSslProxy(proxyStr);

        FirefoxOptions capability = new FirefoxOptions();
        capability.setCapability(CapabilityType.PROXY, proxy);

        String urlString = "http://www.yahoo.com/";
        WebDriver driver = new FirefoxDriver(capability);
        driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);

        driver.get(urlString);

        driver.close();
        System.out.println("Driver closed");

        proxyServer.abort();
        System.out.println("Proxy stopped");
    }

    @Test
    public void testWithHttpClient() throws Exception {
        mockServer.when(request()
                        .withMethod("GET")
                        .withPath("/success"),
                Times.exactly(1))
                .respond(response()
                                .withStatusCode(200)
                                .withBody("Success!")
                );
        // final String url = "https://www.exceptional.io/api/errors?" +
        // "api_key="+"9848f38fb5ad1db0784675b75b9152c87dc1eb95"+"&protocol_version=6";

        final String url = "http://127.0.0.1:" + mockServerPort + "/success";
        final String[] sites = { url };// "https://www.google.com.ua"};//"https://exceptional.io"};//"http://www.google.com.ua"};
        for (final String site : sites) {
            runSiteTestWithHttpClient(site);
        }
    }

    private void runSiteTestWithHttpClient(final String site) throws Exception {
        // HttpResponse response = client.execute(get);

        // assertEquals(200, response.getStatusLine().getStatusCode());
        // EntityUtils.consume(response.getEntity());
        /*
         * final HttpProxyServer ssl = new DefaultHttpProxyServer(PROXY_PORT,
         * null, null, new SslHandshakeHandlerFactory(), new HttpRequestFilter()
         * {
         * 
         * @Override public void filter(HttpRequest httpRequest) {
         * System.out.println("Request went through proxy"); } });
         */

        final HttpProxyServer proxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public io.netty.handler.codec.http.HttpResponse proxyToServerRequest(
                                    HttpObject httpObject) {
                                System.out.println("Request with through proxy");
                                return null;
                            }
                        };
                    }
                }).start();

        try {
            final HttpClient client = TestUtils.createProxiedHttpClient(proxy.getListenAddress().getPort());

            // final HttpPost get = new HttpPost(site);
            final HttpGet get = new HttpGet(site);

            // client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
            // new HttpHost("75.101.134.244", PROXY_PORT));
            // new HttpHost("localhost", PROXY_PORT, "https"));
            HttpResponse response = client.execute(get);
            assertEquals(200, response.getStatusLine().getStatusCode());
            final HttpEntity entity = response.getEntity();
            final String body = IOUtils.toString(entity.getContent(), StandardCharsets.US_ASCII);
            EntityUtils.consume(entity);

            log.info("Consuming entity -- got body: {}", body);
            EntityUtils.consume(response.getEntity());

            log.info("Stopping proxy");
        } finally {
            if (proxy != null) {
                proxy.abort();
            }
        }
    }

    // @Test
    public void testWithWebDriver() {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        Proxy proxy = new Proxy();
        proxy.setProxyType(Proxy.ProxyType.MANUAL);
        String proxyStr = String.format("localhost:%d", proxyServer.getListenAddress().getPort());
        proxy.setHttpProxy(proxyStr);
        proxy.setSslProxy(proxyStr);

        FirefoxOptions capability = new FirefoxOptions();
        capability.setCapability(CapabilityType.PROXY, proxy);

        final String urlString = "http://www.yahoo.com/";

        // Note this will actually launch a browser!!
        final WebDriver driver = new FirefoxDriver(capability);
        driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);

        driver.get(urlString);
        final String source = driver.getPageSource();

        // Just make sure it got something within reason.
        assertThat(source.length(), greaterThan(100));
        driver.close();

        proxyServer.abort();
    }

}
