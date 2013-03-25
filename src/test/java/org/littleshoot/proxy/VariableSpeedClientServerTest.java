package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Arrays;

import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.junit.Ignore;
import org.junit.Test;

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
    private static final long CONTENT_LENGTH = 1000000000L;
    
    @Test
    public void testServerFaster() throws Exception {
        startServer();
        Thread.yield();
        TestUtils.startProxyServer(PROXY_PORT);
        Thread.yield();
        Thread.sleep(400);
        final DefaultHttpClient client = new DefaultHttpClient();
        final HttpHost proxy = new HttpHost("127.0.0.1", PROXY_PORT, "http");
        client.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,proxy);
        client.getParams().setParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 50000);
        client.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT, 120000);
        
        final String endpoint = "http://127.0.0.1:"+PORT+"/";
        final HttpGet get = new HttpGet(endpoint);
        final HttpResponse response = client.execute(get);

        final HttpEntity entity = response.getEntity();
        final long cl = entity.getContentLength();
        assertEquals(CONTENT_LENGTH, cl);
        
        final InputStream content = 
            new ThrottledInputStream(entity.getContent(), 10 * 1000);
        final byte[] input = new byte[100000];
        int read = content.read(input);
        
        int bytesRead = 0;
        while (read != -1) {
            bytesRead += read;
            read = content.read(input);
        }
        assertEquals(CONTENT_LENGTH, bytesRead);
        //final String body = IOUtils.toString(entity.getContent());
        EntityUtils.consume(entity);
        content.close();
    }

    private void startServer() throws Exception {
        final Thread t = new Thread(new Runnable() {

            @Override
            public void run() {
                try {
                    startServerOnThread();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
        }, "Test-Server-Thread");
        t.setDaemon(true);
        t.start();
    }

    private void startServerOnThread() throws Exception {
        final ServerSocket server = new ServerSocket(PORT);
        server.setSoTimeout(100000);
        final Socket sock = server.accept();
        final InputStream is = sock.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(is));
        String cur = br.readLine();
        while (StringUtils.isNotBlank(cur)) {
            cur = br.readLine();
        }
        final OutputStream os = sock.getOutputStream();
        final String responseHeaders = 
             "HTTP/1.1 200 OK\r\n"+
            "Date: Sun, 20 Jan 2013 00:16:23 GMT\r\n"+
            "Expires: -1\r\n"+
            "Cache-Control: private, max-age=0\r\n"+
            "Content-Type: text/html; charset=ISO-8859-1\r\n"+
            "Server: gws\r\n"+
            "Content-Length: "+CONTENT_LENGTH+"\r\n\r\n"; // 10 gigs or so.
        
        os.write(responseHeaders.getBytes(Charset.forName("UTF-8")));
        
        final byte[] bytes = new byte[100000];
        Arrays.fill(bytes, (byte)77);
        final int limit = (int) (CONTENT_LENGTH/bytes.length);
        
        for (int i = 0; i < limit; i++) {
            os.write(bytes);
        }
        os.close();
    }
}
