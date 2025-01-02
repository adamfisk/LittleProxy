package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.util.EntityUtils;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.remote.CapabilityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

import static java.time.Duration.ofSeconds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.littleshoot.proxy.TestUtils.createProxiedHttpClient;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end test making sure the proxy is able to service simple HTTP requests
 * and stop at the end. Made into a unit test from isopov and nasis's
 * contributions at: <a href="https://github.com/adamfisk/LittleProxy/issues/36">...</a>
 */
public final class EndToEndStoppingTest {
    private static final Logger log = LoggerFactory.getLogger(EndToEndStoppingTest.class);

    private ClientAndServer mockServer;
    private int mockServerPort;

    @BeforeEach
    void setUp() {
        mockServer = new ClientAndServer(0);
        mockServerPort = mockServer.getLocalPort();
    }

    @AfterEach
    void tearDown() {
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

        Proxy proxy = createSeleniumProxy(proxyServer);

        FirefoxOptions capability = new FirefoxOptions();
        capability.setCapability(CapabilityType.PROXY, proxy);

        String urlString = "https://www.yahoo.com/";
        WebDriver driver = new FirefoxDriver(capability);
        driver.manage().timeouts().pageLoadTimeout(ofSeconds(30));

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

        final String url = "http://127.0.0.1:" + mockServerPort + "/success";
        final String[] sites = { url };
        for (final String site : sites) {
            runSiteTestWithHttpClient(site);
        }
    }

    private void runSiteTestWithHttpClient(final String site) throws Exception {
        final HttpProxyServer proxy = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @NonNull
                    @Override
                    public HttpFilters filterRequest(@NonNull HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public io.netty.handler.codec.http.HttpResponse proxyToServerRequest(@NonNull HttpObject httpObject) {
                                System.out.println("Request with through proxy");
                                return null;
                            }
                        };
                    }
                }).start();

        try (CloseableHttpClient client = createProxiedHttpClient(proxy.getListenAddress().getPort())) {
            // final HttpPost get = new HttpPost(site);
            final HttpGet get = new HttpGet(site);

            // client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
            // new HttpHost("75.101.134.244", PROXY_PORT));
            // new HttpHost("localhost", PROXY_PORT, "https"));
            HttpResponse response = client.execute(get);
            assertThat(response.getStatusLine().getStatusCode()).isEqualTo(200);
            final HttpEntity entity = response.getEntity();
            final String body = IOUtils.toString(entity.getContent(), StandardCharsets.US_ASCII);
            EntityUtils.consume(entity);

            log.info("Consuming entity -- got body: {}", body);
            EntityUtils.consume(response.getEntity());

            log.info("Stopping proxy");
        } finally {
            proxy.abort();
        }
    }

    /**
     * This test actually launches a browser!
     */
    @Test
    public void testWithWebDriver() {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        try {
            Proxy proxy = createSeleniumProxy(proxyServer);
            tryProxyWithBrowser(proxy);
        }
        finally {
            proxyServer.abort();
        }
    }

    private static Proxy createSeleniumProxy(HttpProxyServer proxyServer) {
        Proxy proxy = new Proxy();
        proxy.setProxyType(Proxy.ProxyType.MANUAL);
        String proxyStr = String.format("localhost:%d", proxyServer.getListenAddress().getPort());
        proxy.setHttpProxy(proxyStr);
        proxy.setSslProxy(proxyStr);
        return proxy;
    }

    private void tryProxyWithBrowser(Proxy proxy) {
        ChromeOptions options = new ChromeOptions();
        options.setCapability(CapabilityType.PROXY, proxy);
        options.addArguments("--headless=new");

        WebDriver driver = new ChromeDriver(options);
        try {
            driver.manage().timeouts().pageLoadTimeout(ofSeconds(30));

            driver.get("https://github.com/littleProxy/LittleProxy//");
            String source = driver.getPageSource();

            // Just make sure it got something within reason
            assertThat(source).hasSizeGreaterThan(100);
        }
        finally {
            driver.quit();
        }
    }
}
