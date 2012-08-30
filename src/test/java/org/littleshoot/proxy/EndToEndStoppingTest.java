package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.TimeUnit;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.junit.Test;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.CapabilityType;
import org.openqa.selenium.remote.DesiredCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End to end test making sure the proxy is able to service simple HTTP 
 * requests and stop at the end. Made into a unit test from isopov and nasis's
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
        HttpProxyServer proxyServer = new DefaultHttpProxyServer(port);
        proxyServer.start();

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
        
        final int PROXY_PORT = 10200;
        final HttpClient client = new DefaultHttpClient();

        final HttpGet get = new HttpGet("http://www.google.com.ua");
        HttpResponse response = client.execute(get);
        
        assertEquals(200, response.getStatusLine().getStatusCode());
        EntityUtils.consume(response.getEntity());

        final HttpProxyServer proxy = 
            new DefaultHttpProxyServer(PROXY_PORT, new HttpRequestFilter() {

            public void filter(HttpRequest httpRequest) {
                System.out.println("Request went through proxy");
            }
        });

        proxy.start();
        client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, 
            new HttpHost("localhost", PROXY_PORT));
        response = client.execute(get);
        assertEquals(200, response.getStatusLine().getStatusCode());
        
        log.info("Consuming entity");
        EntityUtils.consume(response.getEntity());
        
        log.info("Stopping proxy");
        proxy.stop();
    }
    
    @Test
    public void testWithWebDriver() throws Exception {
        int port = 9090;
        HttpProxyServer proxyServer = new DefaultHttpProxyServer(port);
        proxyServer.start();

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