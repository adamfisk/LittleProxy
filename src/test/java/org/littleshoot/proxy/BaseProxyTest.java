package org.littleshoot.proxy;

import static org.junit.Assert.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpVersion;

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
import java.nio.charset.Charset;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;

import org.apache.commons.codec.binary.Hex;
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
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.util.EntityUtils;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.NetworkUtils;
import org.littleshoot.proxy.impl.ProxyUtils;

/**
 * Base for tests that test the proxy. This base class encapsulates all of the
 * tests and test conditions.. Sub-classes should provide different
 * {@link #setUp()} and {@link #tearDown()} methods for testing different
 * configurations of the proxy (e.g. single versus chained, tunneling, etc.).
 */
public abstract class BaseProxyTest {
    protected static final String DEFAULT_RESOURCE = "/";

    protected static final AtomicInteger WEB_SERVER_PORT_SEQ = new AtomicInteger(
            50000);
    protected static final AtomicInteger WEB_SERVER_HTTPS_PORT_SEQ = new AtomicInteger(
            53000);
    protected static final AtomicInteger PROXY_SERVER_PORT_SEQ = new AtomicInteger(
            56000);

    protected int webServerPort = 0;
    protected int httpsWebServerPort = 0;
    protected int proxyServerPort = 0;

    protected HttpHost webHost = new HttpHost("127.0.0.1",
            webServerPort);
    protected HttpHost httpsWebHost = new HttpHost(
            "127.0.0.1", httpsWebServerPort, "https");

    /**
     * The server used by the tests.
     */
    protected HttpProxyServer proxyServer;

    /**
     * The web server that provides the back-end.
     */
    private Server webServer;

    private AtomicInteger bytesReceivedFromClient;
    private AtomicInteger requestsReceivedFromClient;
    private AtomicInteger bytesSentToServer;
    private AtomicInteger requestsSentToServer;
    private AtomicInteger bytesReceivedFromServer;
    private AtomicInteger responsesReceivedFromServer;
    private AtomicInteger bytesSentToClient;
    private AtomicInteger responsesSentToClient;
    private AtomicInteger clientConnects;
    private AtomicInteger clientSSLHandshakeSuccesses;
    private AtomicInteger clientDisconnects;

    @Before
    public void runSetUp() throws Exception {
        bytesReceivedFromClient = new AtomicInteger(0);
        requestsReceivedFromClient = new AtomicInteger(0);
        bytesSentToServer = new AtomicInteger(0);
        requestsSentToServer = new AtomicInteger(0);
        bytesReceivedFromServer = new AtomicInteger(0);
        responsesReceivedFromServer = new AtomicInteger(0);
        bytesSentToClient = new AtomicInteger(0);
        responsesSentToClient = new AtomicInteger(0);
        clientConnects = new AtomicInteger(0);
        clientSSLHandshakeSuccesses = new AtomicInteger(0);
        clientDisconnects = new AtomicInteger(0);

        // Set up new ports for everything based on sequence numbers
        webServerPort = WEB_SERVER_PORT_SEQ.getAndIncrement();
        httpsWebServerPort = WEB_SERVER_HTTPS_PORT_SEQ.getAndIncrement();
        proxyServerPort = PROXY_SERVER_PORT_SEQ.getAndIncrement();

        webHost = new HttpHost("127.0.0.1",
                webServerPort);
        httpsWebHost = new HttpHost(
                "127.0.0.1", httpsWebServerPort, "https");

        webServer = TestUtils.startWebServer(webServerPort,
                httpsWebServerPort);
        setUp();
    }

    protected abstract void setUp() throws Exception;

    @After
    public void runTearDown() throws Exception {
        try {
            tearDown();
        } finally {
            try {
                if (this.proxyServer != null) {
                    this.proxyServer.stop();
                }
            } finally {
                if (this.webServer != null) {
                    webServer.stop();
                }
            }
        }
    }

    protected void tearDown() throws Exception {
    }

    /**
     * Override this to specify a username to use when authenticating with
     * proxy.
     * 
     * @return
     */
    protected String getUsername() {
        return null;
    }

    /**
     * Override this to specify a password to use when authenticating with
     * proxy.
     * 
     * @return
     */
    protected String getPassword() {
        return null;
    }

    @Test
    public void testSimpleGetRequest() throws Exception {
        compareProxiedAndUnproxiedGET(webHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testSimpleGetRequestOverHTTPS() throws Exception {
        compareProxiedAndUnproxiedGET(httpsWebHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testSimplePostRequest() throws Exception {
        compareProxiedAndUnproxiedPOST(webHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testSimplePostRequestOverHTTPS() throws Exception {
        compareProxiedAndUnproxiedPOST(httpsWebHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testProxyWithBadAddress()
            throws Exception {
        final String response =
                httpPostWithApacheClient(new HttpHost("test.localhost"),
                        DEFAULT_RESOURCE, true);

        // The second expected response is what squid returns here.
        assertTrue(
                "Received: " + response,
                response.startsWith("Bad Gateway")
                        ||
                        response.contains("The requested URL could not be retrieved"));
    }

    /**
     * Tests the proxy both with chunking and without to make sure it's working
     * identically with both.
     * 
     * @throws Exception
     *             If any unexpected error occurs.
     */
    public void testProxyChunkAndNo() throws Exception {
        final byte[] baseResponse = rawResponse("i.i.com.com", 80, true,
                HttpVersion.HTTP_1_0);
        final byte[] proxyResponse = rawResponse("127.0.0.1",
                proxyServerPort, false,
                HttpVersion.HTTP_1_1);
        final ByteBuf wrappedBase = Unpooled.wrappedBuffer(baseResponse);
        final ByteBuf wrappedProxy = Unpooled.wrappedBuffer(proxyResponse);

        assertEquals("Lengths not equal", wrappedBase.capacity(),
                wrappedProxy.capacity());
        assertEquals("Not equal:\n" +
                Hex.encodeHexString(baseResponse) + "\n\n\n" +
                Hex.encodeHexString(proxyResponse), wrappedBase,
                wrappedProxy);

        final ByteArrayInputStream baseBais = new ByteArrayInputStream(
                baseResponse);
        // final String baseStr = IOUtils.toString(new
        // GZIPInputStream(baseBais));
        final String baseStr = IOUtils.toString(baseBais);
        final File baseFile = new File("base_sandberg.jpg");
        baseFile.deleteOnExit();
        final FileWriter baseFileWriter = new FileWriter(baseFile);
        baseFileWriter.write(baseStr);
        baseFileWriter.close();

        final ByteArrayInputStream proxyBais = new ByteArrayInputStream(
                proxyResponse);
        // final String proxyStr = IOUtils.toString(new
        // GZIPInputStream(proxyBais));
        final String proxyStr = IOUtils.toString(proxyBais);
        final File proxyFile = new File("proxy_sandberg.jpg");
        proxyFile.deleteOnExit();
        final FileWriter proxyFileWriter = new FileWriter(proxyFile);
        proxyFileWriter.write(proxyStr);
        proxyFileWriter.close();

        assertEquals("Decoded proxy string does not equal expected",
                baseStr, proxyStr);
    }

    private String httpPostWithApacheClient(
            HttpHost host, String resourceUrl, boolean isProxied)
            throws Exception {
        String username = getUsername();
        String password = getPassword();
        final DefaultHttpClient httpClient = buildHttpClient();
        try {
            if (isProxied) {
                final HttpHost proxy = new HttpHost("127.0.0.1",
                        proxyServerPort);
                httpClient.getParams().setParameter(
                        ConnRoutePNames.DEFAULT_PROXY, proxy);
                if (username != null && password != null) {
                    httpClient.getCredentialsProvider()
                            .setCredentials(
                                    new AuthScope("127.0.0.1",
                                            proxyServerPort),
                                    new UsernamePasswordCredentials(username,
                                            password));
                }
            }

            final HttpPost request = new HttpPost(resourceUrl);
            request.getParams().setParameter(
                    CoreConnectionPNames.CONNECTION_TIMEOUT, 5000);
            // request.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT,
            // 15000);
            final StringEntity entity = new StringEntity("adsf", "UTF-8");
            entity.setChunked(true);
            request.setEntity(entity);

            final HttpResponse response = httpClient.execute(host, request);
            final HttpEntity resEntity = response.getEntity();
            final String str = EntityUtils.toString(resEntity);
            return str;
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }

    private String httpGetWithApacheClient(HttpHost host,
            String resourceUrl, boolean isProxied)
            throws Exception {
        String username = getUsername();
        String password = getPassword();
        DefaultHttpClient httpClient = buildHttpClient();
        try {
            if (isProxied) {
                HttpHost proxy = new HttpHost("127.0.0.1", proxyServerPort);
                httpClient.getParams().setParameter(
                        ConnRoutePNames.DEFAULT_PROXY, proxy);
                if (username != null && password != null) {
                    httpClient.getCredentialsProvider()
                            .setCredentials(
                                    new AuthScope("127.0.0.1",
                                            proxyServerPort),
                                    new UsernamePasswordCredentials(username,
                                            password));
                }
            }

            HttpGet request = new HttpGet(resourceUrl);
            request.getParams().setParameter(
                    CoreConnectionPNames.CONNECTION_TIMEOUT, 5000);
            // request.getParams().setParameter(CoreConnectionPNames.SO_TIMEOUT,
            // 15000);

            HttpResponse response = httpClient.execute(host, request);
            HttpEntity resEntity = response.getEntity();
            return EntityUtils.toString(resEntity);
        } finally {
            httpClient.getConnectionManager().shutdown();
        }
    }

    private DefaultHttpClient buildHttpClient() throws Exception {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        SSLSocketFactory sf = new SSLSocketFactory(
                new TrustSelfSignedStrategy(), new X509HostnameVerifier() {
                    public boolean verify(String arg0, SSLSession arg1) {
                        return true;
                    }

                    public void verify(String host, String[] cns,
                            String[] subjectAlts)
                            throws SSLException {
                    }

                    public void verify(String host, X509Certificate cert)
                            throws SSLException {
                    }

                    public void verify(String host, SSLSocket ssl)
                            throws IOException {
                    }
                });
        Scheme scheme = new Scheme("https", 443, sf);
        httpClient.getConnectionManager().getSchemeRegistry().register(scheme);
        return httpClient;
    }

    private void compareProxiedAndUnproxiedPOST(HttpHost host,
            String resourceUrl) throws Exception {
        String unproxiedResponse = httpPostWithApacheClient(host,
                resourceUrl, false);
        String proxiedResponse = httpPostWithApacheClient(host,
                resourceUrl, true);
        assertEquals(unproxiedResponse, proxiedResponse);
        checkStatistics(host);
    }

    private void compareProxiedAndUnproxiedGET(HttpHost host,
            String resourceUrl) throws Exception {
//        String unproxiedResponse = httpGetWithApacheClient(host,
//                resourceUrl, false);
        String proxiedResponse = httpGetWithApacheClient(host,
                resourceUrl, true);
        //assertEquals(unproxiedResponse, proxiedResponse);
        checkStatistics(host);
    }

    private void checkStatistics(HttpHost host) {
        boolean isHTTPS = host.getSchemeName().equalsIgnoreCase("HTTPS");
        int numberOfExpectedClientInteractions = isAuthenticating() ? 2 : 1;
        int numberOfExpectedServerInteractions = isHTTPS && !isChained() ? 0
                : 1;
        assertTrue(bytesReceivedFromClient.get() > 0);
        assertEquals(numberOfExpectedClientInteractions,
                requestsReceivedFromClient.get());
        assertTrue(bytesSentToServer.get() > 0);
        assertEquals(numberOfExpectedServerInteractions,
                requestsSentToServer.get());
        assertTrue(bytesReceivedFromServer.get() > 0);
        assertEquals(numberOfExpectedServerInteractions,
                responsesReceivedFromServer.get());
        assertTrue(bytesSentToClient.get() > 0);
        assertEquals(numberOfExpectedClientInteractions,
                responsesSentToClient.get());
    }

    /**
     * Override this to indicate that the proxy is chained.
     */
    protected boolean isChained() {
        return false;
    }

    /**
     * Override this to indicate that the test uses authentication.
     */
    protected boolean isAuthenticating() {
        return false;
    }

    private byte[] rawResponse(final String url, final int port,
            final boolean simulateProxy, final HttpVersion httpVersion)
            throws UnknownHostException, IOException {
        final Socket sock = new Socket(url, port);
        System.out.println("Connected...");
        final OutputStream os = sock.getOutputStream();
        final Writer writer = new OutputStreamWriter(os);
        final String uri = "http://www.google.com/search?hl=en&client=safari&rls=en-us&q=headphones&aq=f&oq=&aqi=";
        if (simulateProxy) {
            final String noHostUri = ProxyUtils.stripHost(uri);
            writeHeader(writer, "GET " + noHostUri + " HTTP/1.1\r\n");
        }
        else {
            writeHeader(writer, "GET " + uri + " HTTP/1.1\r\n");
        }
        writeHeader(writer,
                "Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n");
        writeHeader(writer,
                "Accept-Charset: ISO-8859-1,utf-8;q=0.7,*;q=0.7\r\n");
        writeHeader(writer, "Accept-Encoding: gzip,deflate\r\n");
        writeHeader(writer, "Accept-Language: en-us,en;q=0.5\r\n");
        writeHeader(writer, "Host: www.google.com\r\n");
        writeHeader(writer, "Keep-Alive: 300\r\n");
        if (simulateProxy) {
            writeHeader(writer, "Connection: keep-alive\r\n");
        }
        else {
            writeHeader(writer, "Proxy-Connection: keep-alive\r\n");
        }
        writeHeader(
                writer,
                "User-Agent: Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10.5; en-US; rv:1.9.0.14) Gecko/2009082706 Firefox/3.0.14\r\n");
        if (simulateProxy) {
            final InetAddress address = NetworkUtils.getLocalHost();// InetAddress.getLocalHost();
            final String host = address.getHostName();
            final String via = "1.1 " + host;
            writeHeader(writer, "Via: " + via + "\r\n");
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
                    System.out.println("READ HEADER: " + headerLine);
                    if (!headerLine.startsWith("HTTP"))
                    {
                        headers.put(
                                StringUtils.substringBefore(headerLine, ":")
                                        .trim(),
                                StringUtils.substringAfter(headerLine, ":")
                                        .trim());
                    }
                    else {
                        /*
                         * if (httpVersion == HttpVersion.HTTP_1_0) {
                         * assertEquals("HTTP/1.0",
                         * StringUtils.substringBefore(headerLine, " ")); } else
                         * if (httpVersion == HttpVersion.HTTP_1_1) {
                         * assertEquals("HTTP/1.1",
                         * StringUtils.substringBefore(headerLine, " ")); } else
                         * {
                         * fail("Unexpected HTTP version in line: "+headerLine);
                         * }
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
        if (file.isFile())
            file.delete();
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
                fail("Weird encoding: " + encoding);
                throw new RuntimeException("Weird encoding: " + encoding);
            }
        }
        else {
            throw new RuntimeException(
                    "Weird headers. Can't determin length in " + headers);
        }

        int remaining = limit;
        System.out.println("Reading body of length: " + limit);
        while (remaining > 0) {
            System.out.println("Remaining: " + remaining);
            final long transferred = fc.transferFrom(src, 0, remaining);
            System.out.println("Read: " + transferred);
            remaining -= transferred;
        }
        System.out.println("CLOSING CHANNEL");
        fc.close();

        System.out.println("READ BODY!");
        return IOUtils.toByteArray(new FileInputStream(file));
    }

    private byte[] readAllChunks(final InputStream is, final File file)
            throws IOException {
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
            System.out.println("READ: " + transferred);
            System.out.println("TOTAL: " + totalTransferred);
            readCrLf(is);
        }
        // fc.close();
        return IOUtils.toByteArray(new FileInputStream(file));
    }

    private void readCrLf(final InputStream is) throws IOException {
        final char cr = (char) is.read();
        final char lf = (char) is.read();
        if (cr != '\r' || lf != '\n') {
            final byte[] crlf = new byte[2];
            crlf[0] = (byte) cr;
            crlf[1] = (byte) lf;
            final ByteBuf buf = Unpooled.wrappedBuffer(crlf);
            throw new Error("Did not get expected CRLF!! Instead got hex: " +
                    Hex.encodeHexString(crlf) + " and str: "
                    + buf.toString(Charset.forName("US-ASCII")));
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
                final ByteBuf buf = Unpooled.wrappedBuffer(bytes);
                System.out.println("BUF IN HEX: " + Hex.encodeHexString(bytes));
                if (StringUtils.isBlank(line)) {
                    return 0;
                }
                final int length = Integer.parseInt(line, 16);
                System.out.println("CHUNK LENGTH: " + length);
                return length;
                // return Integer.parseInt(line);
            }
            else if (curChar == '\r') {
                lastCr = true;
            }
            else {
                lastCr = false;
                curLine.append(curChar);
            }

        }

        throw new IOException("Reached count with current read: "
                + curLine.toString());
    }

    private void writeHeader(final Writer writer, final String header)
            throws IOException {
        System.out.print("WRITING HEADER: " + header);
        writer.write(header);
    }

    protected HttpProxyServerBootstrap bootstrapProxy() {
        return DefaultHttpProxyServer.bootstrap().plusActivityTracker(
                new ActivityTracker() {
                    @Override
                    public void bytesReceivedFromClient(
                            FlowContext flowContext,
                            int numberOfBytes) {
                        bytesReceivedFromClient.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void requestReceivedFromClient(
                            FlowContext flowContext,
                            HttpRequest httpRequest) {
                        requestsReceivedFromClient.incrementAndGet();
                    }

                    @Override
                    public void bytesSentToServer(FullFlowContext flowContext,
                            int numberOfBytes) {
                        bytesSentToServer.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void requestSentToServer(
                            FullFlowContext flowContext,
                            HttpRequest httpRequest) {
                        requestsSentToServer.incrementAndGet();
                    }

                    @Override
                    public void bytesReceivedFromServer(
                            FullFlowContext flowContext,
                            int numberOfBytes) {
                        bytesReceivedFromServer.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void responseReceivedFromServer(
                            FullFlowContext flowContext,
                            io.netty.handler.codec.http.HttpResponse httpResponse) {
                        responsesReceivedFromServer.incrementAndGet();
                    }

                    @Override
                    public void bytesSentToClient(FlowContext flowContext,
                            int numberOfBytes) {
                        bytesSentToClient.addAndGet(numberOfBytes);
                    }

                    @Override
                    public void responseSentToClient(
                            FlowContext flowContext,
                            io.netty.handler.codec.http.HttpResponse httpResponse) {
                        responsesSentToClient.incrementAndGet();
                    }

                    @Override
                    public void clientConnected(InetSocketAddress clientAddress) {
                        clientConnects.incrementAndGet();
                    }

                    @Override
                    public void clientSSLHandshakeSucceeded(
                            InetSocketAddress clientAddress,
                            SSLSession sslSession) {
                        clientSSLHandshakeSuccesses.incrementAndGet();
                    }

                    @Override
                    public void clientDisconnected(
                            InetSocketAddress clientAddress,
                            SSLSession sslSession) {
                        clientDisconnects.incrementAndGet();
                    }
                });
    }

}
