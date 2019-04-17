package org.littleshoot.proxy;

import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.junit.Ignore;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;

/**
 * Tests cases where either the client or the server is slower than the other.
 * 
 * Ignored because this doesn't quite trigger OOME for some reason. It also
 * takes too long to include in normal tests.
 */
@Ignore
public class VariableSpeedClientServerTest {

    private static final int PORT = TestUtils.randomPort();
    private static final int PROXY_PORT = TestUtils.randomPort();
    private static final int CONTENT_LENGTH = 1000000000;

    @Test
    public void testServerFaster() throws Exception {
        doTest(PORT, PROXY_PORT, false);
    }

    @Test
    public void testServerSlower() throws Exception {
        doTest(PORT, PROXY_PORT, true);
    }

    private void doTest(int port, int proxyPort, boolean slowServer)
            throws Exception {
        startServer(port, slowServer);
        Thread.yield();
        DefaultHttpProxyServer.bootstrap().withPort(proxyPort).start();
        Thread.yield();
        Thread.sleep(400);
        final DefaultHttpClient client = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", proxyPort, "http");
        client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
        client.getParams().setParameter(
                CoreConnectionPNames.CONNECTION_TIMEOUT, 50000);
        // client.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT,
        // 120000);

        System.out
                .println("------------------ Memory Usage At Beginning ------------------");
        TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();

        final String endpoint = "http://127.0.0.1:" + port + "/";
        final HttpPost post = new HttpPost(endpoint);
        post.setEntity(new InputStreamEntity(new InputStream() {
            private int remaining = CONTENT_LENGTH;

            @Override
            public int read() {
                if (remaining > 0) {
                    remaining -= 1;
                    return 77;
                } else {
                    return 0;
                }
            }

            @Override
            public int available() {
                return remaining;
            }
        }, CONTENT_LENGTH));
        final HttpResponse response = client.execute(post);

        final HttpEntity entity = response.getEntity();
        final long cl = entity.getContentLength();
        assertEquals(CONTENT_LENGTH, cl);

        int bytesRead = 0;
        try (InputStream content = slowServer ? new ThrottledInputStream(entity.getContent(), 10 * 1000) : entity.getContent()) {
            final byte[] input = new byte[100000];
            int read = content.read(input);

            while (read != -1) {
                bytesRead += read;
                read = content.read(input);
            }
        }
        assertEquals(CONTENT_LENGTH, bytesRead);
        // final String body = IOUtils.toString(entity.getContent());
        EntityUtils.consume(entity);
        System.out
                .println("------------------ Memory Usage At Beginning ------------------");
        TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();
    }

    private void startServer(final int port, final boolean slowReader) {
        final Thread t = new Thread(() -> {
            try {
                startServerOnThread(port, slowReader);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Test-Server-Thread");
        t.setDaemon(true);
        t.start();
    }

    private void startServerOnThread(int port, boolean slowReader)
            throws Exception {
        try (ServerSocket server = new ServerSocket(port)) {
            server.setSoTimeout(100000);
            final Socket sock = server.accept();
            InputStream is = sock.getInputStream();
            if (slowReader) {
                is = new ThrottledInputStream(is, 10 * 1000);
            }
            BufferedReader br = new BufferedReader(new InputStreamReader(is));
            while (br.read() != 0) {
            }
            final OutputStream os = sock.getOutputStream();
            final String responseHeaders =
                    "HTTP/1.1 200 OK\r\n" +
                            "Date: Sun, 20 Jan 2013 00:16:23 GMT\r\n" +
                            "Expires: -1\r\n" +
                            "Cache-Control: private, max-age=0\r\n" +
                            "Content-Type: text/html; charset=ISO-8859-1\r\n" +
                            "Server: gws\r\n" +
                            "Content-Length: " + CONTENT_LENGTH + "\r\n\r\n"; // 10
            // gigs
            // or
            // so.

            os.write(responseHeaders.getBytes(Charset.forName("UTF-8")));

            int bufferSize = 100000;
            final byte[] bytes = new byte[bufferSize];
            Arrays.fill(bytes, (byte) 77);
            int remainingBytes = CONTENT_LENGTH;

            while (remainingBytes > 0) {
                int numberOfBytesToWrite = Math.min(remainingBytes, bufferSize);
                os.write(bytes, 0, numberOfBytesToWrite);
                remainingBytes -= numberOfBytesToWrite;
            }
            os.close();
        }
    }
}
