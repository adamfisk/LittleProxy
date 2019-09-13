package org.littleshoot.proxy;

import com.sun.management.UnixOperatingSystemMXBean;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.CredentialsProvider;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.ssl.SSLContextBuilder;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.littleshoot.proxy.extras.SelfSignedSslEngineSource;

import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.util.Objects;

public class TestUtils {

    public static final RequestConfig REQUEST_TIMEOUT_CONFIG = RequestConfig.custom().setConnectTimeout(5000).build();

    private TestUtils() {
    }

    /**
     * Creates and starts an embedded web server on a JVM-assigned HTTP ports.
     * Each response has a body that indicates how many bytes were received with
     * a message like "Received x bytes\n".
     * 
     * @return Instance of Server
     */
    public static Server startWebServer() {
        return startWebServer(false);
    }

    /**
     * Creates and starts an embedded web server on a JVM-assigned HTTP ports.
     * Creates and starts embedded web server that is running on given port.
     * Each response has a body that contains the specified contents.
     *
     * @return Instance of Server
     */
    public static Server startWebServerWithResponse(byte[] content) {
        return startWebServerWithResponse(false, content);
    }

    /**
     * Creates and starts an embedded web server on JVM-assigned HTTP and HTTPS ports.
     * Each response has a body that indicates how many bytes were received with a message like
     * "Received x bytes\n".
     *
     * @param enableHttps if true, an HTTPS connector will be added to the web server
     * @return Instance of Server
     */
    public static Server startWebServer(boolean enableHttps) {
        final Server httpServer = new Server(0);

        httpServer.setHandler(new AbstractHandler() {
            public void handle(String target,
                               Request baseRequest,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                if (request.getRequestURI().contains("hang")) {
                    System.out.println("Hanging as requested");
                    try {
                        Thread.sleep(90000);
                    } catch (InterruptedException ie) {
                        System.out.println("Stopped hanging due to interruption");
                    }
                }
                
                long numberOfBytesRead = 0;
                try (InputStream in = new BufferedInputStream(request.getInputStream())) {
                    while (in.read() != -1) {
                        numberOfBytesRead += 1;
                    }
                }
                System.out.println("Done reading # of bytes: "
                        + numberOfBytesRead);
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
                byte[] content = ("Received " + numberOfBytesRead + " bytes\n").getBytes();
                response.addHeader("Content-Length", Integer.toString(content.length));
                response.getOutputStream().write(content);
            }
        });

        if (enableHttps) {
            // Add SSL connector
            SslContextFactory sslContextFactory = new SslContextFactory.Server();

            SelfSignedSslEngineSource contextSource = new SelfSignedSslEngineSource();
            SSLContext sslContext = contextSource.getSslContext();

            sslContextFactory.setSslContext(sslContext);
            ServerConnector connector = new ServerConnector(httpServer, sslContextFactory);
            connector.setPort(0);
            connector.setIdleTimeout(0);
            httpServer.addConnector(connector);
        }

        try {
            httpServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Error starting Jetty web server", e);
        }

        return httpServer;
    }

    /**
     * Creates and starts an embedded web server on JVM-assigned HTTP and HTTPS ports.
     * Each response has a body that contains the specified contents.
     *
     * @param enableHttps if true, an HTTPS connector will be added to the web server
     * @param content The response the server will return
     * @return Instance of Server
     */
    public static Server startWebServerWithResponse(boolean enableHttps, final byte[] content) {
        final Server httpServer = new Server(0);
        httpServer.setHandler(new AbstractHandler() {
            public void handle(String target,
                               Request baseRequest,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
                if (request.getRequestURI().contains("hang")) {
                    System.out.println("Hanging as requested");
                    try {
                        Thread.sleep(90000);
                    } catch (InterruptedException ie) {
                        System.out.println("Stopped hanging due to interruption");
                    }
                }

                long numberOfBytesRead = 0;
                try (InputStream in = new BufferedInputStream(request.getInputStream())) {
                    while (in.read() != -1) {
                        numberOfBytesRead += 1;
                    }
                }
                System.out.println("Done reading # of bytes: "
                        + numberOfBytesRead);
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);

                response.addHeader("Content-Length", Integer.toString(content.length));
                response.getOutputStream().write(content);
            }
        });

        if (enableHttps) {
            // Add SSL connector
            SslContextFactory sslContextFactory = new SslContextFactory.Server();

            SelfSignedSslEngineSource contextSource = new SelfSignedSslEngineSource();
            SSLContext sslContext = contextSource.getSslContext();

            sslContextFactory.setSslContext(sslContext);
            ServerConnector connector = new ServerConnector(httpServer, sslContextFactory);
            connector.setPort(0);
            connector.setIdleTimeout(0);
            httpServer.addConnector(connector);
        }

        try {
            httpServer.start();
        } catch (Exception e) {
            throw new RuntimeException("Error starting Jetty web server", e);
        }

        return httpServer;
    }

    /**
     * Finds the port the specified server is listening for HTTP connections on.
     *
     * @param webServer started web server
     * @return HTTP port, or -1 if no HTTP port was found
     */
    public static int findLocalHttpPort(Server webServer) {
        for (Connector connector : webServer.getConnectors()) {
            if (!Objects.equals(connector.getDefaultConnectionFactory().getProtocol(), "SSL")) {
                return ((ServerConnector) connector).getLocalPort();
            }
        }

        return -1;
    }

    /**
     * Finds the port the specified server is listening for HTTPS connections on.
     *
     * @param webServer started web server
     * @return HTTP port, or -1 if no HTTPS port was found
     */
    public static int findLocalHttpsPort(Server webServer) {
        for (Connector connector : webServer.getConnectors()) {
            if (Objects.equals(connector.getDefaultConnectionFactory().getProtocol(), "SSL")) {
                return ((ServerConnector) connector).getLocalPort();
            }
        }

        return -1;
    }

    /**
     * Creates instance HttpClient that is configured to use proxy server. The
     * proxy server should run on 127.0.0.1 and given port
     * 
     * @param port
     *            the proxy port
     * @return instance of HttpClient
     */
    public static CloseableHttpClient createProxiedHttpClient(final int port) throws Exception {
        return buildHttpClient(true, false, port, null, null);
    }

    public static int randomPort() {
        final SecureRandom secureRandom = new SecureRandom();
        for (int i = 0; i < 20; i++) {
            // The +1 on the random int is because
            // Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE -- caught
            // by FindBugs.
            final int randomPort = 1024 + (Math.abs(secureRandom.nextInt() + 1) % 60000);
            try (ServerSocket sock = new ServerSocket()) {
                sock.bind(new InetSocketAddress("127.0.0.1", randomPort));
                return sock.getLocalPort();
            } catch (final IOException ignored) {
            }
        }

        // If we can't grab one of our securely chosen random ports, use
        // whatever port the OS assigns.
        try (ServerSocket sock = new ServerSocket()) {
            sock.bind(null);
            return sock.getLocalPort();
        } catch (final IOException e) {
            return 1024 + (Math.abs(secureRandom.nextInt() + 1) % 60000);
        }
    }
    
    public static long getOpenFileDescriptorsAndPrintMemoryUsage() {
        // Below courtesy of:
        // http://stackoverflow.com/questions/10999076/programmatically-print-the-heap-usage-that-is-typically-printed-on-jvm-exit-when
        MemoryUsage mu = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage();
        MemoryUsage muNH = ManagementFactory.getMemoryMXBean()
                .getNonHeapMemoryUsage();
        System.out.println("Init :" + mu.getInit() + "\nMax :" + mu.getMax()
                + "\nUsed :" + mu.getUsed() + "\nCommitted :"
                + mu.getCommitted() + "\nInit NH :" + muNH.getInit()
                + "\nMax NH :" + muNH.getMax() + "\nUsed NH:" + muNH.getUsed()
                + "\nCommitted NH:" + muNH.getCommitted());

        OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

        if (osMxBean instanceof UnixOperatingSystemMXBean) {
            UnixOperatingSystemMXBean unixOsMxBean = (UnixOperatingSystemMXBean) osMxBean;
            return unixOsMxBean.getOpenFileDescriptorCount();
        } else {
            throw new UnsupportedOperationException("Unable to determine number of open file handles on non-Unix system");
        }
    }

    /**
     * Determines if we are running on a Unix-like operating system that exposes a {@link com.sun.management.UnixOperatingSystemMXBean}.
     *
     * @return true if this is a Unix OS and the JVM exposes a {@link com.sun.management.UnixOperatingSystemMXBean}, otherwise false.
     */
    public static boolean isUnixManagementCapable() {
        OperatingSystemMXBean osMxBean = ManagementFactory.getOperatingSystemMXBean();

        return (osMxBean instanceof UnixOperatingSystemMXBean);
    }

    /**
     * Creates a DefaultHttpClient instance.
     * 
     * @return instance of ClosableHttpClient
     */
    public static CloseableHttpClient buildHttpClient(boolean isProxied, boolean supportSsl, int proxyPort,
                                                      String username, String password) throws Exception {

        HttpClientBuilder builder = HttpClientBuilder.create()
                .setSSLContext(SSLContextBuilder.create()
                        .loadTrustMaterial(new TrustSelfSignedStrategy())
                        .build());

        if(supportSsl){
            builder.setSSLHostnameVerifier(new NoopHostnameVerifier());
        }

        if (isProxied) {
            HttpHost proxy = new HttpHost("127.0.0.1", proxyPort);
            builder.setProxy(proxy);
            if (username != null && password != null) {
                CredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                credentialsProvider.setCredentials(
                        new AuthScope("127.0.0.1", proxyPort),
                        new UsernamePasswordCredentials(username, password));
                builder.setDefaultCredentialsProvider(credentialsProvider);
            }
        }

        return builder.build();
    }
}
