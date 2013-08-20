package org.littleshoot.proxy;

import java.io.IOException;
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

import io.netty.handler.codec.http.HttpRequest;

public class TestUtils {

    private TestUtils() {}

    /**
     *  Creates and starts a proxy server that listens on given port.
     * @param port The port
     * @return The instance of proxy server
     */
    public static HttpProxyServer startProxyServer(int port) {
        final DefaultHttpProxyServer proxyServer = new DefaultHttpProxyServer(port);
        proxyServer.start(true, true);
        return proxyServer;
    }

    /**
     *  Creates and starts a proxy server that listens on given port.
     * @param port The port
     * @param chainProxyHostAndPort Proxy relay
     * @return The instance of proxy server
     */
    public static HttpProxyServer startProxyServer(int port, final String chainProxyHostAndPort) {
        final DefaultHttpProxyServer proxyServer = new DefaultHttpProxyServer(port, null, new ChainProxyManager() {
            public String getChainProxy(HttpRequest httpRequest) {
                return chainProxyHostAndPort;
            }

            public void onCommunicationError(String hostAndPort) {
            }
        }, null, null);
        proxyServer.start(true, true);
        return proxyServer;
    }

    /**
     * Creates and starts embedded web server that is running on given port.
     * Each response has empty body with HTTP OK status.
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
     * including an SSL connector on the other given port. Each response has
     * empty body with HTTP OK status.
     * 
     * @param port
     *            The port
     * @param sslPort
     *            (optional) The ssl port
     * @return Instance of Server
     * @throws Exception
     *             if failed to start
     */
    public static Server startWebServer(final int port, final Integer sslPort) throws Exception {
        final Server httpServer = new Server(port);
        httpServer.setHandler(new AbstractHandler() {
            public void handle(String target, Request baseRequest,
                    HttpServletRequest request, HttpServletResponse response)
                    throws IOException, ServletException {
                response.setStatus(HttpServletResponse.SC_OK);
                baseRequest.setHandled(true);
            }
        });
        if (sslPort != null) {
            // Add SSL connector
            org.eclipse.jetty.util.ssl.SslContextFactory sslContextFactory = new org.eclipse.jetty.util.ssl.SslContextFactory();

            org.littleshoot.proxy.SslContextFactory scf = new org.littleshoot.proxy.SslContextFactory(new SelfSignedKeyStoreManager());
            SSLContext sslContext = scf.getServerContext();

            sslContextFactory.setSslContext(sslContext);
            SslSocketConnector connector = new SslSocketConnector(sslContextFactory);
            connector.setPort(sslPort);
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
    public static HttpClient createProxiedHttpClient(final int port) throws Exception {
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
    public static HttpClient createProxiedHttpClient(final int port, final boolean supportSSL) throws Exception {
        final HttpClient httpclient = new DefaultHttpClient();
        // Note: we use 127.0.0.1 here because on OS X, using straight up
        // localhost yields a connect exception.
        final HttpHost proxy = new HttpHost("127.0.0.1", port, "http");
        httpclient.getParams().setParameter(ConnRoutePNames.DEFAULT_PROXY,
                proxy);
        if (supportSSL) {
            SSLSocketFactory sf = new SSLSocketFactory(new TrustSelfSignedStrategy());
            sf.setHostnameVerifier(new X509HostnameVerifier() {
                public boolean verify(String arg0, SSLSession arg1) {
                    return true;
                }
                public void verify(String host, String[] cns, String[] subjectAlts) throws SSLException {
                }
                public void verify(String host, X509Certificate cert) throws SSLException {
                }
                public void verify(String host, SSLSocket ssl) throws IOException {
                }
            });
            Scheme scheme = new Scheme("https", 443, sf);
            httpclient.getConnectionManager().getSchemeRegistry().register(scheme);
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
