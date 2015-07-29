package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.ReadableByteChannel;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHost;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tests the default HTTP proxy.
 */
public class HttpProxyTest {

    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private static final String HOST = "http://opsgenie.com/status/ping";
    
    /**
     * Tests the proxy both with chunking and without to make sure it's working
     * identically with both.
     *
     * @throws Exception If any unexpected error occurs.
     */
    public void testProxyChunkAndNo() throws Exception {
        final HttpProxyServer server = startHttpProxy();
        try {
            final byte[] baseResponse = rawResponse("i.i.com.com", 80, true, HttpVersion.HTTP_1_0);
            final byte[] proxyResponse = rawResponse("127.0.0.1", 8080, false, HttpVersion.HTTP_1_1);
            final ChannelBuffer wrappedBase = ChannelBuffers.wrappedBuffer(baseResponse);
            final ChannelBuffer wrappedProxy = ChannelBuffers.wrappedBuffer(proxyResponse);
    
            assertEquals("Lengths not equal", wrappedBase.capacity(), wrappedProxy.capacity());
            assertEquals("Not equal:\n"+
                ChannelBuffers.hexDump(wrappedBase)+"\n\n\n"+
                ChannelBuffers.hexDump(wrappedProxy), wrappedBase, wrappedProxy);
    
            final ByteArrayInputStream baseBais = new ByteArrayInputStream(baseResponse);
            //final String baseStr = IOUtils.toString(new GZIPInputStream(baseBais));
            final String baseStr = IOUtils.toString(baseBais);
            final File baseFile = new File("base_sandberg.jpg");
            baseFile.deleteOnExit();
            final FileWriter baseFileWriter = new FileWriter(baseFile);
            baseFileWriter.write(baseStr);
            baseFileWriter.close();
            //System.out.println("RESPONSE:\n"+baseStr);
    
            final ByteArrayInputStream proxyBais = new ByteArrayInputStream(proxyResponse);
            //final String proxyStr = IOUtils.toString(new GZIPInputStream(proxyBais));
            final String proxyStr = IOUtils.toString(proxyBais);
            final File proxyFile = new File("proxy_sandberg.jpg");
            proxyFile.deleteOnExit();
            final FileWriter proxyFileWriter = new FileWriter(proxyFile);
            proxyFileWriter.write(proxyStr);
            proxyFileWriter.close();
            //System.out.println("RESPONSE:\n"+proxyStr);
            
            assertEquals("Decoded proxy string does not equal expected", baseStr, proxyStr);
        } finally {
            server.stop();
        }
    }

    @Test
    public void testProxyWithApacheHttpClientChunkedRequests() throws Exception {
        final HttpProxyServer server = startHttpProxy();

        try {
            String baseResponse = httpPostWithApacheClient(false);
            String proxyResponse = httpPostWithApacheClient(true);
            assertEquals(baseResponse, proxyResponse);
        } finally {
            server.stop();
        }
    }
    
    @Test
    public void testProxyWithApacheHttpClientChunkedRequestsBadAddress() throws Exception {
        final HttpProxyServer server = startHttpProxy();
        
        try {
            final String response =
                httpPostWithApacheClient(true, "http://test.localhost");
            
            // The second expected response is what squid returns here.
            assertTrue("Received: "+response, response.startsWith("Bad Gateway") ||
                    response.contains("The requested URL could not be retrieved"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void testProxyAuthorizationWithPostRequests() throws Exception{
        String username = "user1";
        String password = "pass1";

        final HttpProxyServer server = 
                startHttpProxyWithCredentials(username, password);

        try {
            String baseResponse = httpPostWithApacheClient(false);
            String proxyResponse = 
                httpPostWithApacheClient(true, username, password);
            assertEquals(baseResponse, proxyResponse);
        } finally {
            server.stop();
        }
    }
    
    @Test
    public void testProxyAuthorizationWithGetRequests() throws Exception{
        String username = "user1";
        String password = "pass1";

        final HttpProxyServer server = 
            startHttpProxyWithCredentials(username, password);

        try {
            String baseResponse = httpGetWithApacheClient(false);
            String proxyResponse = httpGetWithApacheClient(true, username, password);
            assertEquals(baseResponse, proxyResponse);
        } finally {
            server.stop();
        }
    }
    
    @Test
    public void testConfiguredProxyCacheManager() throws IOException {
        LittleProxyConfig.setProxyCacheManagerClass( SimpleProxyCacheManager.class.getCanonicalName() );
        final HttpProxyServer server =  startHttpProxy();
        // clear the test manager from config, so it does not bleed into other tests
        LittleProxyConfig.setProxyCacheManagerClass( null );
        
        try {
            String baseResponse = httpPostWithApacheClient(false);
            String proxyResponse = httpPostWithApacheClient(true);
            assertEquals(baseResponse, proxyResponse);
        } finally {
            server.stop();
        }
        
        assertEquals( Arrays.asList("http://opsgenie.com/status/ping"), SimpleProxyCacheManager.requests );
    }

    private String httpPostWithApacheClient(final boolean isProxy) 
        throws IOException {
        return httpPostWithApacheClient(isProxy, null, null, HOST);
    }
    
    private String httpPostWithApacheClient(final boolean isProxy,
        final String host) throws IOException {
        return httpPostWithApacheClient(isProxy, null, null, host);
    }
    
    private String httpPostWithApacheClient(final boolean isProxy, 
        final String username, final String password) throws IOException {
        return httpPostWithApacheClient(isProxy, username, password, HOST);
    }
    
    private String httpPostWithApacheClient(final boolean isProxy, 
        final String username, final String password, final String host) 
                throws IOException {
        final DefaultHttpClient httpclient = new DefaultHttpClient();
        try {
            if (isProxy) {
                final HttpHost proxy = new HttpHost("127.0.0.1", 8080);
                httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
                if(username != null && password != null){
                    httpclient.getCredentialsProvider().setCredentials(
                        new AuthScope("127.0.0.1", 8080), 
                        new UsernamePasswordCredentials(username, password));
                }
            }
            
            final HttpPost httppost = new HttpPost(host);
            final StringEntity entity = new StringEntity("adsf", "UTF-8");
            entity.setChunked(true);
            httppost.setEntity(entity);
            
            final HttpResponse response = httpclient.execute(httppost);
            final HttpEntity resEntity = response.getEntity();
            final String str = EntityUtils.toString(resEntity);
            return str;
        } finally {
            httpclient.getConnectionManager().shutdown();
        }
    }
    
    private String httpGetWithApacheClient(final boolean isProxy) 
        throws IOException {
        return httpGetWithApacheClient(isProxy, null, null);
    }
    
    private String httpGetWithApacheClient(boolean isProxy, String username, 
        String password) throws IOException {
        DefaultHttpClient httpclient = new DefaultHttpClient();
        try {
            if (isProxy) {
                HttpHost proxy = new HttpHost("127.0.0.1", 8080);
                httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY, proxy);
                if(username != null && password != null){
                    httpclient.getCredentialsProvider().setCredentials(new AuthScope("127.0.0.1", 8080), 
                        new UsernamePasswordCredentials(username, password));
                }
            }
            
            HttpGet httppost = new HttpGet(HOST);

            HttpResponse response = httpclient.execute(httppost);
            HttpEntity resEntity = response.getEntity();
            return EntityUtils.toString(resEntity);
        } finally {
            httpclient.getConnectionManager().shutdown();
        }
    }

    private byte[] rawResponse(final String url, final int port,
        final boolean simulateProxy, final HttpVersion httpVersion)
        throws UnknownHostException, IOException {
        //final InetSocketAddress isa = new InetSocketAddress("127.0.0.1", 8080);
        final Socket sock = new Socket(url, port);
        System.out.println("Connected...");
        final OutputStream os = sock.getOutputStream();
        final Writer writer = new OutputStreamWriter(os);
        //final String uri = "http://i.i.com.com/cnwk.1d/i/bto/20091023/sandberg.jpg";
        final String uri = "http://www.google.com/search?hl=en&client=safari&rls=en-us&q=headphones&aq=f&oq=&aqi=";
        if (simulateProxy) {
            final String noHostUri = ProxyUtils.stripHost(uri);
            writeHeader(writer, "GET "+noHostUri+" HTTP/1.1\r\n");
        }
        else {
            writeHeader(writer, "GET "+uri+" HTTP/1.1\r\n");
        }
        writeHeader(writer, "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n");
        writeHeader(writer, "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.7\r\n");
        writeHeader(writer, "Accept-Encoding: gzip,deflate\r\n");
        writeHeader(writer, "Accept-Language: en-us,en;q=0.5\r\n");
        //writeHeader(writer, "Cookie: XCLGFbrowser=Cg8ILkmHQruNAAAAeAs; globid=1.1WJrGuYpPuQP4SL3\r\n");

        //writeHeader(writer, "Cookie: [XCLGFbrowser=Cg8ILkmHQruNAAAAeAs; globid=1.1WJrGuYpPuQP4SL3]\r\n");
        //writeHeader(writer, "Host: i.i.com.com\r\n");
        writeHeader(writer, "Host: www.google.com\r\n");
        writeHeader(writer, "Keep-Alive: 300\r\n");
        if (simulateProxy) {
            writeHeader(writer, "Connection: keep-alive\r\n");
        }
        else {
            writeHeader(writer, "Proxy-Connection: keep-alive\r\n");
        }
        writeHeader(writer, "User-Agent: Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10.5; en-US; rv:1.9.0.14) Gecko/2009082706 Firefox/3.0.14\r\n");
        if (simulateProxy) {
            final InetAddress address = InetAddress.getLocalHost();
            final String host = address.getHostName();
            final String via = "1.1 " + host;
            writeHeader(writer, "Via: "+via+"\r\n");
        }
        writeHeader(writer, "\r\n");
        writer.flush();

        System.out.println("READING RESPONSE HEADERS");
        final Map<String, String> headers = new HashMap<String, String>();
        StringBuilder curLine = new StringBuilder();
        final InputStream is = sock.getInputStream();
        boolean lastCr = false;
        boolean haveCrLn = false;
        while (true) {
            final char curChar = (char) is.read();
            if (lastCr && curChar == '\n') {
                if (haveCrLn) {
                    System.out.println("GOT END OF HEADERS!!");
                    break;
                }
                else {
                    final String headerLine = curLine.toString();
                    System.out.println("READ HEADER: "+headerLine);
                    if (!headerLine.startsWith("HTTP"))
                        {
                        headers.put(
                            StringUtils.substringBefore(headerLine, ":").trim(),
                            StringUtils.substringAfter(headerLine, ":").trim());
                        }
                    else {
                        /*
                        if (httpVersion == HttpVersion.HTTP_1_0) {
                            assertEquals("HTTP/1.0", 
                                StringUtils.substringBefore(headerLine, " "));
                        }
                        else if (httpVersion == HttpVersion.HTTP_1_1) {
                            assertEquals("HTTP/1.1", 
                            StringUtils.substringBefore(headerLine, " "));
                        }
                        else {
                            fail("Unexpected HTTP version in line: "+headerLine);
                        }
                        */
                    }
                    curLine = new StringBuilder();
                    haveCrLn = true;
                }
            }
            else if (curChar == '\r') {
                lastCr = true;
            }
            else {
                lastCr = false;
                haveCrLn = false;
                curLine.append(curChar);
            }
        }

        final File file = new File("chunked_test_file");
        file.deleteOnExit();
        if (file.isFile()) file.delete();
        final FileChannel fc =
            new FileOutputStream(file).getChannel();

        final ReadableByteChannel src = Channels.newChannel(is);

        final int limit;
        if (headers.containsKey("Content-Length") &&
            !headers.containsKey("Transfer-Encoding")) {
            limit = Integer.parseInt(headers.get("Content-Length").trim());
        }
        else if (headers.containsKey("Transfer-Encoding")) {
            final String encoding = headers.get("Transfer-Encoding");
            if (encoding.trim().equalsIgnoreCase("chunked")) {
                return readAllChunks(is, file);
            }
            else {
                fail("Weird encoding: "+encoding);
                throw new RuntimeException("Weird encoding: "+encoding);
            }
        }
        else {
            throw new RuntimeException("Weird headers. Can't determin length in "+headers);
        }

        int remaining = limit;
        System.out.println("Reading body of length: "+limit);
        while (remaining > 0) {
            System.out.println("Remaining: "+remaining);
            final long transferred = fc.transferFrom(src, 0, remaining);
            System.out.println("Read: "+transferred);
            remaining -= transferred;
        }
        System.out.println("CLOSING CHANNEL");
        fc.close();

        System.out.println("READ BODY!");
        return IOUtils.toByteArray(new FileInputStream(file));
    }

    private byte[] readAllChunks(final InputStream is, final File file) throws IOException {
        final FileChannel fc = new FileOutputStream(file).getChannel();
        int totalTransferred = 0;
        int index = 0;
        while (true) {
            final int length = readChunkLength(is);
            if (length == 0) {
                System.out.println("GOT CHUNK LENGTH 0!!!");
                readCrLf(is);
                break;
            }
            final ReadableByteChannel src = Channels.newChannel(is);
            final long transferred = fc.transferFrom(src, index, length);
            if (transferred != length) {
                throw new RuntimeException("Could not read expected length!!");
            }
            index += transferred;
            totalTransferred += transferred;
            System.out.println("READ: "+transferred);
            System.out.println("TOTAL: "+totalTransferred);
            readCrLf(is);
        }
        //fc.close();
        return IOUtils.toByteArray(new FileInputStream(file));
    }

    private void readCrLf(final InputStream is) throws IOException {
        final char cr = (char) is.read();
        final char lf = (char) is.read();
        if (cr != '\r' || lf != '\n') {
            final byte[] crlf = new byte[2];
            crlf[0] = (byte) cr;
            crlf[1] = (byte) lf;
            final ChannelBuffer buf = ChannelBuffers.wrappedBuffer(crlf);
            throw new Error("Did not get expected CRLF!! Instead got hex: "+
                ChannelBuffers.hexDump(buf)+" and str: "+buf.toString(java.nio.charset.Charset.forName("US-ASCII")));
        }
    }

    private int readChunkLength(final InputStream is) throws IOException {
        final StringBuilder curLine = new StringBuilder(8);
        boolean lastCr = false;
        int count = 0;
        while (true && count < 20) {
            final char curChar = (char) is.read();
            count++;
            if (lastCr && curChar == '\n') {
                final String line = curLine.toString();
                final byte[] bytes = line.getBytes();
                final ChannelBuffer buf = ChannelBuffers.wrappedBuffer(bytes);
                System.out.println("BUF IN HEX: "+ChannelBuffers.hexDump(buf));
                if (StringUtils.isBlank(line)) {
                    return 0;
                }
                final int length = Integer.parseInt(line, 16);
                System.out.println("CHUNK LENGTH: "+length);
                return length;
                //return Integer.parseInt(line);
            }
            else if (curChar == '\r') {
                lastCr = true;
            }
            else {
                lastCr = false;
                curLine.append(curChar);
            }

        }

        throw new IOException("Reached count with current read: "+curLine.toString());
    }

    private void writeHeader(final Writer writer, final String header)
        throws IOException {
        System.out.print("WRITING HEADER: "+header);
        writer.write(header);
    }

    private HttpProxyServer startHttpProxyWithCredentials(final String userName, 
        final String password) {
        final HttpProxyServer server = new DefaultHttpProxyServer(8080);
        server.addProxyAuthenticationHandler(new ProxyAuthorizationHandler() {
            public boolean authenticate(String u, String p) {
                return userName.equals(u) && password.equals(p);
            }
        });
        server.start();
        checkServer(8080);
        return server;
    }

    private HttpProxyServer startHttpProxy() {
        final HttpProxyServer server = new DefaultHttpProxyServer(8080);
        server.start();
        
        checkServer(8080);
        return server;
    }
    

    private void checkServer(final int port) {
        int tries = 1;
        while (true) {
            final Socket sock = new Socket();
            try {
                sock.connect(new InetSocketAddress(port), 50);
                sock.close();
                log.info("CONNECTED IN {} ms", tries*100);
                break;
            } catch (IOException e) {
            }
            tries++;
            if (tries > 200) {
                throw new Error("Server didn't come up?");
            }
        }
    }
}



