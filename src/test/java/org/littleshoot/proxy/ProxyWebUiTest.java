package org.littleshoot.proxy;

import static org.junit.Assert.*;
import static org.hamcrest.Matchers.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpHeaders.Names;
import io.netty.handler.codec.http.HttpHeaders.Values;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.remote.DesiredCapabilities;

public class ProxyWebUiTest {

    private static final String REDIRECT_PATH = "/";

    private HttpFiltersAdapter answerRedirectFilter(HttpRequest originalRequest) {
        return new HttpFiltersAdapter(originalRequest) {
            @Override
            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                HttpResponse answer = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
                HttpHeaders.setHeader(answer, HttpHeaders.Names.LOCATION, REDIRECT_PATH);
                HttpHeaders.setHeader(answer, Names.CONNECTION, Values.CLOSE);
                return answer;
            }
        };
    }

    private HttpFiltersAdapter answerContentFilter(HttpRequest originalRequest) {
        return new HttpFiltersAdapter(originalRequest) {
            @Override
            public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                ByteBuf buffer = Unpooled.wrappedBuffer("CONTENT".getBytes());
                HttpResponse answer = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buffer);
                HttpHeaders.setContentLength(answer, buffer.readableBytes());
                return answer;
            }
        };
    }

    @Test
    public void testRedirectWithWebDriver() throws Exception {
        HttpProxyServer proxyServer = null;
        WebDriver driver = null;
        try {

            HttpFiltersSourceAdapter filtersSource = new HttpFiltersSourceAdapter() {
                @Override
                public HttpFilters filterRequest(HttpRequest originalRequest) {
                    if (REDIRECT_PATH.equals(originalRequest.getUri())) {
                        return answerContentFilter(originalRequest);
                    } else {
                        return answerRedirectFilter(originalRequest);
                    }
                }

            };
            proxyServer = DefaultHttpProxyServer.bootstrap()//
                    .withFiltersSource(filtersSource)//
                    .withPort(0)//
                    .start();

            DesiredCapabilities capability = DesiredCapabilities.htmlUnit();
            driver = new FirefoxDriver(capability);
            driver.manage().timeouts().pageLoadTimeout(5, TimeUnit.SECONDS);

            String urlStr = String.format("http://localhost:%d/somewhere", proxyServer.getListenAddress().getPort());
            driver.get(urlStr);
            String source = driver.getPageSource();

            assertThat(source, containsString("CONTENT"));

        } finally {
            if (driver != null) {
                driver.close();
            }
            if (proxyServer != null) {
                proxyServer.abort();
            }
        }
    }

}
