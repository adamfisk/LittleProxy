package org.littleshoot.proxy;

import org.apache.http.HttpHost;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.HttpClientUtils;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.IOException;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

@FixMethodOrder(MethodSorters.JVM)
public class ThrottlingTest {
    private static final long THROTTLED_READ_BYTES_PER_SECOND = 25000L;
    private static final long THROTTLED_WRITE_BYTES_PER_SECOND = 25000L;

    // throttling is not guaranteed to be exact, so allow some variation in the amount of time the call takes. since we want
    // these tests to take just a few seconds, allow significant variation. even with this large variation, if throttling
    // is broken it should take much less time than expected.
    private static final double ALLOWABLE_VARIATION = 0.30;

    private Server writeWebServer;
    private Server readWebServer;

    private byte[] largeData;

    private int msToWriteThrottled;
    private int msToReadThrottled;

    // time to allow for an unthrottled local request
    private static final int UNTRHOTTLED_REQUEST_TIME_MS = 1000;

    private int writeWebServerPort;
    private int readWebServerPort;

    @Before
    public void setUp() {
        // Set up some large data
        largeData = new byte[100000];
        for (int i = 0; i < largeData.length; i++) {
            largeData[i] = 1 % 256;
        }

        msToWriteThrottled = largeData.length  * 1000 / (int)THROTTLED_WRITE_BYTES_PER_SECOND;
        msToReadThrottled = largeData.length  * 1000 / (int)THROTTLED_READ_BYTES_PER_SECOND;

        writeWebServer = TestUtils.startWebServer(false);
        writeWebServerPort = TestUtils.findLocalHttpPort(writeWebServer);

        readWebServer = TestUtils.startWebServerWithResponse(false, largeData);
        readWebServerPort = TestUtils.findLocalHttpPort(readWebServer);
    }

    @After
    public void tearDown() throws Exception {
        try {
            if (writeWebServer != null) {
                writeWebServer.stop();
            }
        } finally {
            if (readWebServer != null) {
                readWebServer.stop();
            }
        }
    }

    @Test
    public void aWarmUpTest() throws IOException {
        // a "warm-up" test so the first test's results are not skewed due to classloading, etc. guaranteed to run
        // first with the @FixMethodOrder(MethodSorters.NAME_ASCENDING) annotation on the class.

        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(0, THROTTLED_WRITE_BYTES_PER_SECOND)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        HttpGet request = createHttpGet();
        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        EntityUtils.consumeQuietly(httpClient.execute(new HttpHost("127.0.0.1", writeWebServerPort), request).getEntity());

        EntityUtils.consumeQuietly(httpClient.execute(new HttpHost("127.0.0.1", readWebServerPort), request).getEntity());
    }

    @Test
    public void testThrottledWrite() throws Exception {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(0, THROTTLED_WRITE_BYTES_PER_SECOND)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpPost request = createHttpPost();

        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        long start = System.currentTimeMillis();
        final org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        writeWebServerPort), request);
        long finish = System.currentTimeMillis();

        assertEquals("Received " + largeData.length + " bytes\n",
                EntityUtils.toString(response.getEntity()));

        assertThat("Expected throttled write to complete in approximately " + msToWriteThrottled + "ms" + " but took " + (finish - start) + "ms",
                (double)(finish - start), both(greaterThan(msToWriteThrottled * (1 - ALLOWABLE_VARIATION))).and(
                        lessThan(msToWriteThrottled * (1 + ALLOWABLE_VARIATION))));

        proxyServer.abort();
    }

    @Test
    public void testUnthrottledWrite() throws Exception {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpPost request = createHttpPost();

        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        long start = System.currentTimeMillis();
        final org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        writeWebServerPort), request);
        long finish = System.currentTimeMillis();

        assertEquals("Received " + largeData.length + " bytes\n",
                EntityUtils.toString(response.getEntity()));

        assertThat("Unthrottled write took " + (finish - start) + "ms, but expected to complete in " + UNTRHOTTLED_REQUEST_TIME_MS + "ms",
                finish - start, lessThan((long) UNTRHOTTLED_REQUEST_TIME_MS));

        proxyServer.abort();
    }

    @Test
    public void testThrottledRead() throws Exception {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(THROTTLED_READ_BYTES_PER_SECOND, 0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        long start = System.currentTimeMillis();
        final org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        readWebServerPort), request);
        byte[] readContent = new byte[100000];

        int bytesRead = 0;
        while (bytesRead < largeData.length) {
            int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
            bytesRead += read;
        }

        long finish = System.currentTimeMillis();

        assertThat("Expected throttled read to complete in approximately " + msToReadThrottled + "ms" + " but took " + (finish - start) + "ms",
                (double)(finish - start), both(greaterThan(msToReadThrottled * (1 - ALLOWABLE_VARIATION)))
                        .and(lessThan(msToReadThrottled * (1 + ALLOWABLE_VARIATION))));

        proxyServer.abort();
    }

    @Test
    public void testUnthrottledRead() throws Exception {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        long start = System.currentTimeMillis();
        final org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        readWebServerPort), request);

        byte[] readContent = new byte[100000];
        int bytesRead = 0;
        while (bytesRead < largeData.length) {
            int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
            bytesRead += read;
        }

        long finish = System.currentTimeMillis();

        assertThat("Unthrottled read took " + (finish - start) + "ms, but expected to complete in " + UNTRHOTTLED_REQUEST_TIME_MS + "ms",
                finish - start, lessThan((long)UNTRHOTTLED_REQUEST_TIME_MS));

        proxyServer.abort();
    }

    @Test
    public void testChangeThrottling() throws IOException {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(THROTTLED_READ_BYTES_PER_SECOND, 0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        long firstStart = System.currentTimeMillis();
        org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        readWebServerPort), request);
        byte[] readContent = new byte[100000];

        int bytesRead = 0;
        while (bytesRead < largeData.length) {
            int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
            bytesRead += read;
        }

        long firstFinish = System.currentTimeMillis();

        HttpClientUtils.closeQuietly(response);

        proxyServer.setThrottle(THROTTLED_READ_BYTES_PER_SECOND * 2, 0);

        long secondStart = System.currentTimeMillis();
        response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        readWebServerPort), request);
        readContent = new byte[100000];

        bytesRead = 0;
        while (bytesRead < largeData.length) {
            int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
            bytesRead += read;
        }

        long secondFinish = System.currentTimeMillis();

        HttpClientUtils.closeQuietly(response);

        assertThat("Expected second read to take approximately half as long as first throttled read. First read took " + (firstFinish - firstStart) + "ms" + " but second read took " + (secondFinish - secondStart) + "ms",
                (double)(firstFinish - firstStart) / 2, both(greaterThan((secondFinish - secondStart) * (1 - ALLOWABLE_VARIATION)))
                        .and(lessThan((secondFinish - secondStart) * (1 + ALLOWABLE_VARIATION))));

        proxyServer.abort();
    }

    @Test
    public void testDisableThrottling() throws IOException {
        HttpProxyServer proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .withThrottling(THROTTLED_READ_BYTES_PER_SECOND, 0)
                .start();

        int proxyPort = proxyServer.getListenAddress().getPort();

        final HttpGet request = createHttpGet();

        DefaultHttpClient httpClient = createHttpClient(proxyPort);

        long firstStart = System.currentTimeMillis();
        org.apache.http.HttpResponse response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        readWebServerPort), request);
        byte[] readContent = new byte[100000];

        int bytesRead = 0;
        while (bytesRead < largeData.length) {
            int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
            bytesRead += read;
        }

        long firstFinish = System.currentTimeMillis();

        HttpClientUtils.closeQuietly(response);

        proxyServer.setThrottle(0, 0);

        long secondStart = System.currentTimeMillis();
        response = httpClient.execute(
                new HttpHost("127.0.0.1",
                        readWebServerPort), request);
        readContent = new byte[100000];

        bytesRead = 0;
        while (bytesRead < largeData.length) {
            int read = response.getEntity().getContent().read(readContent, bytesRead, largeData.length - bytesRead);
            bytesRead += read;
        }

        long secondFinish = System.currentTimeMillis();

        HttpClientUtils.closeQuietly(response);

        assertThat("Expected second read to complete within " + UNTRHOTTLED_REQUEST_TIME_MS + "ms, without throttling. First read took "
                        + (firstFinish - firstStart) + "ms" + ". Second read took " + (secondFinish - secondStart) + "ms",
                secondFinish - secondStart, lessThan((long) UNTRHOTTLED_REQUEST_TIME_MS));

        proxyServer.abort();

    }

    private HttpGet createHttpGet() {
        final HttpGet request = new HttpGet("/");
        request.getParams().setParameter(
                CoreConnectionPNames.CONNECTION_TIMEOUT, 5000);
        return request;
    }

    private HttpPost createHttpPost() {
        final HttpPost request = new HttpPost("/");
        request.getParams().setParameter(
                CoreConnectionPNames.CONNECTION_TIMEOUT, 5000);
        final ByteArrayEntity entity = new ByteArrayEntity(largeData);
        entity.setChunked(true);
        request.setEntity(entity);
        return request;
    }

    private DefaultHttpClient createHttpClient(int proxyPort) {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", proxyPort, "http");
        httpClient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
                proxy);
        return httpClient;
    }
}
