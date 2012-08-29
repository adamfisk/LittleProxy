package org.littleshoot.proxy;

import static org.junit.Assert.*;

import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.junit.Test;

/**
 * End to end test making sure the proxy is able to service simple HTTP 
 * requests and stop at the end. Made into a unit test from isopov's 
 * contribution at: https://github.com/adamfisk/LittleProxy/issues/36
 */
public class EndToEndStoppingTest {

    @Test
    public void test() throws Exception {
        
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
        EntityUtils.consume(response.getEntity());
        proxy.stop();
    }

}