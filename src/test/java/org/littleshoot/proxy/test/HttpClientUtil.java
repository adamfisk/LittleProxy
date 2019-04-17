package org.littleshoot.proxy.test;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.littleshoot.proxy.HttpProxyServer;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Utility methods for creating HTTP clients and sending requests to servers via a LittleProxy instance.
 */
public class HttpClientUtil {
    /**
     * Creates a new HTTP client that uses the specified LittleProxy instance to perform a GET to the specified URL.
     * The HTTP client is closed and discarded after the request is completed.
     *
     * @param url URL to post to
     * @param proxyServer LittleProxy instance through which the GET will be proxied
     * @return the HttpResponse object encapsulating the response from the server
     */
    public static org.apache.http.HttpResponse performHttpGet(String url, HttpProxyServer proxyServer) {
        CloseableHttpClient http = buildHttpClient(proxyServer);

        HttpGet get = new HttpGet(url);

        return performHttpRequest(http, get);
    }

    /**
     * Creates a new HTTP client that uses the specified LittleProxy instance to perform a POST of the specified size to
     * to the URL. The POST body will consist of a meaningless UTF-8-encoded String (currently, the letter 'q'). The
     * HTTP client is closed and discarded after the request is completed.
     *
     * @param url URL to post to
     * @param postSizeInBytes size of the POST body
     * @param proxyServer LittleProxy instance through which the POST will be proxied
     * @return the HttpResponse from the server
     */
    public static org.apache.http.HttpResponse performHttpPost(String url, int postSizeInBytes, HttpProxyServer proxyServer) {
        CloseableHttpClient httpClient = buildHttpClient(proxyServer);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < postSizeInBytes; i++) {
            sb.append('q');
        }

        HttpPost post = new HttpPost(url);
        post.setEntity(new StringEntity(sb.toString(), Charset.forName("UTF-8")));

        return performHttpRequest(httpClient, post);
    }

    /**
     * Returns a new HttpClient that is configured to use the specified LittleProxy instance.
     *
     * @param proxyServer LittleProxy instance through which requests will be proxied
     * @return new HttpClient
     */
    private static CloseableHttpClient buildHttpClient(HttpProxyServer proxyServer) {
        return HttpClients.custom()
                .setProxy(new HttpHost("127.0.0.1", proxyServer.getListenAddress().getPort()))
                .build();
    }

    /**
     * Performs the specified request using the HTTP client. Consumes the response entity and shuts down the HTTP client
     * after the request has been made.
     *
     * @param httpClient HTTP client to use
     * @param request HTTP request to perform
     * @return the HttpResponse from the server
     */
    private static org.apache.http.HttpResponse performHttpRequest(CloseableHttpClient httpClient, HttpUriRequest request) {
        try {
            org.apache.http.HttpResponse hr = httpClient.execute(request);
            HttpEntity responseEntity = hr.getEntity();
            EntityUtils.consume(responseEntity);

            httpClient.close();

            return hr;
        } catch (IOException e) {
            // this is test code; just let all exceptions bubble up the stack, which will cause the test to fail
            throw new RuntimeException("Unable to perform HTTP request", e);
        }
    }
}
