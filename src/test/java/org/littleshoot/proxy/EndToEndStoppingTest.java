package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import org.apache.commons.io.IOUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.util.EntityUtils;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * End to end test making sure the proxy is able to service simple HTTP requests
 * and stop at the end. Made into a unit test from isopov and nasis's
 * contributions at: https://github.com/adamfisk/LittleProxy/issues/36
 */
public class EndToEndStoppingTest {

    private final Logger log = LoggerFactory.getLogger(getClass());

    /**
     * This is a quick test from nasis that exhibits different behavior from
     * unit tests because unit tests call System.exit(). The stop method should
     * stop all non-daemon threads and should cause the JVM to exit without
     * explicitly calling System.exit(), which running as an application
     * properly tests.
     */
    public static void main(final String[] args) throws Exception {
        int port = 9090;
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(port)
                .start();

        Proxy proxy = new Proxy();
        proxy.setProxyType(Proxy.ProxyType.MANUAL);
        String proxyStr = String.format("localhost:%d", port);
        proxy.setHttpProxy(proxyStr);
        proxy.setSslProxy(proxyStr);

        DesiredCapabilities capability = DesiredCapabilities.firefox();
        capability.setCapability(CapabilityType.PROXY, proxy);

        String urlString = "http://www.yahoo.com/";
        WebDriver driver = new FirefoxDriver(capability);
        driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);

        driver.get(urlString);

        driver.close();
        System.out.println("Driver closed");

        proxyServer.stop();
        System.out.println("Proxy stopped");
    }

    @Test
    public void testWithHttpClient() throws Exception {
        // final String url = "https://www.exceptional.io/api/errors?" +
        // "api_key="+"9848f38fb5ad1db0784675b75b9152c87dc1eb95"+"&protocol_version=6";

        final String url = "https://www.exceptional.io";
        final String[] sites = { url };// "https://www.google.com.ua"};//"https://exceptional.io"};//"http://www.google.com.ua"};
        for (final String site : sites) {
            runSiteTestWithHttpClient(site);
        }
    }

    private void runSiteTestWithHttpClient(final String site) throws Exception {
        final int PROXY_PORT = 9097;
        final HttpClient client = TestUtils.createProxiedHttpClient(PROXY_PORT);

        // final HttpPost get = new HttpPost(site);
        final HttpGet get = new HttpGet(site);
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

        final HttpProxyServer plain = DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_PORT)
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(HttpRequest originalRequest) {
                        return new HttpFiltersAdapter(originalRequest) {
                            @Override
                            public io.netty.handler.codec.http.HttpResponse proxyToServerRequest(
                                    HttpObject httpObject) {
                                System.out
                                        .println("Request with through proxy");
                                return null;
                            }
                        };
                    }
                }).start();
        final HttpProxyServer proxy = plain;

        // client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
        // new HttpHost("75.101.134.244", PROXY_PORT));
        // new HttpHost("localhost", PROXY_PORT, "https"));
        HttpResponse response = client.execute(get);
        assertEquals(200, response.getStatusLine().getStatusCode());
        final HttpEntity entity = response.getEntity();
        final String body =
                IOUtils.toString(entity.getContent()).toLowerCase();
        EntityUtils.consume(entity);

        log.info("Consuming entity -- got body: {}", body);
        EntityUtils.consume(response.getEntity());

        log.info("Stopping proxy");
        proxy.stop();
    }

    // @Test
    public void testWithWebDriver() throws Exception {
        int port = 9090;
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(port)
                .start();

        Proxy proxy = new Proxy();
        proxy.setProxyType(Proxy.ProxyType.MANUAL);
        String proxyStr = String.format("localhost:%d", port);
        proxy.setHttpProxy(proxyStr);
        proxy.setSslProxy(proxyStr);

        DesiredCapabilities capability = DesiredCapabilities.firefox();
        capability.setCapability(CapabilityType.PROXY, proxy);

        final String urlString = "http://www.yahoo.com/";

        // Note this will actually launch a browser!!
        final WebDriver driver = new FirefoxDriver(capability);
        driver.manage().timeouts().pageLoadTimeout(30, TimeUnit.SECONDS);

        driver.get(urlString);
        final String source = driver.getPageSource();

        // Just make sure it got something within reason.
        assertTrue(source.length() > 100);
        driver.close();

        proxyServer.stop();
    }

}
