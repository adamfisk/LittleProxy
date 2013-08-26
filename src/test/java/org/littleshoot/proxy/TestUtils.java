package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.http.HttpHost;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.params.ConnRoutePNames;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.ssl.SslSocketConnector;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.SelfSignedKeyStoreManager;

public class TestUtils {

    private TestUtils() {
    }

    public static HttpProxyServer startProxyServer(
            int port) {
        return startProxyServer(TransportProtocol.TCP, port);
    }

    /**
     * Creates and starts a proxy server that listens on given port.
     * 
     * @param port
     *            The port
     * @return The instance of proxy server
     */
    public static HttpProxyServer startProxyServer(
            TransportProtocol transportProtocol, int port) {
        return startProxyServerWithCredentials(transportProtocol, port, null,
                null);
    }

    public static HttpProxyServer startProxyServerWithCredentials(
            int port,
            String username, String password) {
        return startProxyServerWithCredentials(TransportProtocol.TCP, port,
                username, password);
    }

    /**
     * Creates and starts a proxy server that listens on given port and
     * authenticates clients with the given username and password.
     * 
     * @param transportProtocol
     *            the data transport protocol to use
     * @param port
     *            The port
     * @param username
     * @param password
     * @return The instance of proxy server
     */
    public static HttpProxyServer startProxyServerWithCredentials(
            TransportProtocol transportProtocol, int port,
            String username, String password) {
        return startProxyServerWithCredentials(transportProtocol, port, null,
                username, password);
    }

    public static HttpProxyServer startProxyServer(
            int port,
            final String chainProxyHostAndPort) {
        return startProxyServer(TransportProtocol.TCP, port,
                chainProxyHostAndPort);
    }

    /**
     * Creates and starts a proxy server that listens on the given port and
     * chains requests to the proxy at the given chainProxyHostAndPort.
     * 
     * @param transportProtocol
     *            the data transport protocol to use
     * @param port
     *            The port
     * @param chainProxyHostAndPort
     *            Proxy relay
     * @return The instance of proxy server
     */
    public static HttpProxyServer startProxyServer(
            TransportProtocol transportProtocol, int port,
            final String chainProxyHostAndPort) {
        return startProxyServerWithCredentials(transportProtocol, port,
                chainProxyHostAndPort,
                null, null);
    }

    /**
     * Creates and starts a proxy server that listens on the given port, chains
     * requests to the proxy at the given chainProxyHostAndPort, and
     * authenticates clients using the given username and password.
     * 
     * @param transportProtocol
     *            the data transport protocol to use
     * @param port
     *            The port
     * @param chainProxyHostAndPort
     *            Proxy relay
     * @param username
     * @param password
     * @return The instance of proxy server
     */
    public static HttpProxyServer startProxyServerWithCredentials(
            TransportProtocol transportProtocol, int port,
            final String chainProxyHostAndPort, String username,
            String password) {
        DefaultHttpProxyServer proxyServer;
        if (chainProxyHostAndPort != null) {
            proxyServer = new DefaultHttpProxyServer(
                    transportProtocol, port, null,
                    new ChainedProxyManagerAdapter() {
                        public String getHostAndPort(HttpRequest httpRequest) {
                            return chainProxyHostAndPort;
                        }

                        @Override
                        public TransportProtocol getTransportProtocol() {
                            return TransportProtocol.UDT;
                        }
                    }, null, null);
        } else {
            proxyServer = new DefaultHttpProxyServer(transportProtocol, port);
        }
        addAuthentication(proxyServer, username, password);
        proxyServer.start(true, true);
        return proxyServer;
    }

    private static void addAuthentication(HttpProxyServer proxyServer,
            final String username, final String password) {
        if (username != null && password != null) {
            proxyServer.setProxyAuthenticator(new ProxyAuthenticator() {
                public boolean authenticate(String u, String p) {
                    return username.equals(u) && password.equals(p);
                }
            });
        }
    }

    /**
     * Creates and starts embedded web server that is running on given port.
     * Each response has a body that indicates how many bytes were received with
     * a message like "Received x bytes\n".
     * 
     * @param port
     *            The port
     * @return Instance of Server
     * @throws Exception
     *             if failed to start
     */
    public static Server startWebServer(final int port) throws Exception {
        return startWebServer(port, null);
    }

    /**
     * Creates and starts embedded web server that is running on given port,
     * including an SSL connector on the other given port. Each response has a
     * body that indicates how many bytes were received with a message like
     * "Received x bytes\n".
     * 
     * @param port
     *            The port
     * @param sslPort
     *            (optional) The ssl port
     * @return Instance of Server
     * @throws Exception
     *             if failed to start
     */
    public static Server startWebServer(final int port, final Integer sslPort)
            throws Exception {
        final Server httpServer = new Server(port);
        httpServer.setHandler(new AbstractHandler() {
            public void handle(String target, Request baseRequest,
                    HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException {
                long numberOfBytesRead = 0;
                InputStream in = new BufferedInputStream(request
                        .getInputStream());
                while (in.read() != -1) {
                    numberOfBytesRead += 1;
                }
                System.out.println("Done reading # of bytes: "
                        + numberOfBytesRead);
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
                response.getWriter().write(
                        "Received " + numberOfBytesRead + " bytes\n");
            }
        });
        if (sslPort != null) {
            // Add SSL connector
            org.eclipse.jetty.util.ssl.SslContextFactory sslContextFactory = new org.eclipse.jetty.util.ssl.SslContextFactory();

            org.littleshoot.proxy.impl.SslContextFactory scf = new org.littleshoot.proxy.impl.SslContextFactory(
                    new SelfSignedKeyStoreManager());
            SSLContext sslContext = scf.getServerContext();

            sslContextFactory.setSslContext(sslContext);
            SslSocketConnector connector = new SslSocketConnector(
                    sslContextFactory);
            connector.setPort(sslPort);
            /*
             * <p>Ox: For some reason, on OS X, a non-zero timeout can causes
             * sporadic issues. <a href="http://stackoverflow.com/questions
             * /16191236/tomcat-startup-fails
             * -due-to-java-net-socketexception-invalid-argument-on-mac-o">This
             * StackOverflow thread</a> has some insights into it, but I don't
             * quite get it.</p>
             * 
             * <p>This can cause problems with Jetty's SSL handshaking, so I
             * have to set the handshake timeout and the maxIdleTime to 0 so
             * that the SSLSocket has an infinite timeout.</p>
             */
            connector.setHandshakeTimeout(0);
            connector.setMaxIdleTime(0);
            httpServer.addConnector(connector);
        }
        httpServer.start();
        return httpServer;
    }

    /**
     * Creates instance HttpClient that is configured to use proxy server. The
     * proxy server should run on 127.0.0.1 and given port
     * 
     * @param port
     *            the proxy port
     * @return instance of HttpClient
     */
    public static HttpClient createProxiedHttpClient(final int port)
            throws Exception {
        return createProxiedHttpClient(port, false);
    }

    /**
     * Creates instance HttpClient that is configured to use proxy server. The
     * proxy server should run on 127.0.0.1 and given port
     * 
     * @param port
     *            the proxy port
     * @param supportSSL
     *            if true, client will support SSL connections to servers using
     *            self-signed certificates
     * @return instance of HttpClient
     */
    public static HttpClient createProxiedHttpClient(final int port,
            final boolean supportSSL) throws Exception {
        final HttpClient httpclient = new DefaultHttpClient();
        // Note: we use 127.0.0.1 here because on OS X, using straight up
        // localhost yields a connect exception.
        final HttpHost proxy = new HttpHost("127.0.0.1", port, "http");
        httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
                proxy);
        if (supportSSL) {
            SSLSocketFactory sf = new SSLSocketFactory(
                    new TrustSelfSignedStrategy());
            sf.setHostnameVerifier(new X509HostnameVerifier() {
                public boolean verify(String arg0, SSLSession arg1) {
                    return true;
                }

                public void verify(String host, String[] cns,
                        String[] subjectAlts) throws SSLException {
                }

                public void verify(String host, X509Certificate cert)
                        throws SSLException {
                }

                public void verify(String host, SSLSocket ssl)
                        throws IOException {
                }
            });
            Scheme scheme = new Scheme("https", 443, sf);
            httpclient.getConnectionManager().getSchemeRegistry()
                    .register(scheme);
        }
        return httpclient;
    }

    public static int randomPort() {
        final SecureRandom secureRandom = new SecureRandom();
        for (int i = 0; i < 20; i++) {
            // The +1 on the random int is because
            // Math.abs(Integer.MIN_VALUE) == Integer.MIN_VALUE -- caught
            // by FindBugs.
            final int randomPort = 1024 + (Math.abs(secureRandom.nextInt() + 1) % 60000);
            ServerSocket sock = null;
            try {
                sock = new ServerSocket();
                sock.bind(new InetSocketAddress("127.0.0.1", randomPort));
                final int port = sock.getLocalPort();
                return port;
            } catch (final IOException e) {
            } finally {
                if (sock != null) {
                    try {
                        sock.close();
                    } catch (IOException e) {
                    }
                }
            }
        }

        // If we can't grab one of our securely chosen random ports, use
        // whatever port the OS assigns.
        ServerSocket sock = null;
        try {
            sock = new ServerSocket();
            sock.bind(null);
            final int port = sock.getLocalPort();
            return port;
        } catch (final IOException e) {
            return 1024 + (Math.abs(secureRandom.nextInt() + 1) % 60000);
        } finally {
            if (sock != null) {
                try {
                    sock.close();
                } catch (IOException e) {
                }
            }
        }
    }
}
