package org.littleshoot.proxy;

import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

import static org.hamcrest.Matchers.lessThan;
import static org.junit.Assert.assertThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

/**
 * Note - this test only works on UNIX systems because it checks file descriptor
 * counts.
 *
 * It also fails on macOS (tested on 10.14 Mojave).  It works on Ubuntu, and presumably most other *nix systems.
 */
public class IdleTest {
    private static final int NUMBER_OF_CONNECTIONS_TO_OPEN = 2000;

    private Server webServer;
    private int webServerPort = -1;
    private HttpProxyServer proxyServer;

    @Before
    public void setup() throws Exception {
        assumeTrue("Skipping due to non-Unix OS", TestUtils.isUnixManagementCapable());
        assumeFalse("Skipping for travis-ci build", "true".equals(System.getenv("TRAVIS")));
        assumeFalse("Skipping due to Mac OS", System.getProperty("os.name").toLowerCase().contains("mac"));

        webServer = new Server(0);
        webServer.start();
        webServerPort = TestUtils.findLocalHttpPort(webServer);

        proxyServer = DefaultHttpProxyServer.bootstrap()
                .withPort(0)
                .start();
        proxyServer.setIdleConnectionTimeout(10);

    }

    @After
    public void tearDown() throws Exception {
        try {
            if (webServer != null) {
                webServer.stop();
            }
        } finally {
            if (proxyServer != null) {
                proxyServer.abort();
            }
        }
    }

    @Test
    @Ignore("File descriptors vary too much on my laptop, other people saw this problem too: https://github.com/adamfisk/LittleProxy/pull/221")
    public void testFileDescriptorCount() throws Exception {
        System.out
                .println("------------------ Memory Usage At Beginning ------------------");
        long initialFileDescriptors = TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                "127.0.0.1", proxyServer.getListenAddress().getPort()));
        for (int i = 0; i < NUMBER_OF_CONNECTIONS_TO_OPEN; i++) {
            new URL("http://localhost:" + webServerPort)
                    .openConnection(proxy).connect();
        }

        System.gc();
        System.out
                .println("\n\n------------------ Memory Usage Before Idle Timeout ------------------");

        long fileDescriptorsWhileConnectionsOpen = TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();
        Thread.sleep(10000);

        System.gc();
        System.out
                .println("\n\n------------------ Memory Usage After Idle Timeout ------------------");
        long fileDescriptorsAfterConnectionsClosed = TestUtils.getOpenFileDescriptorsAndPrintMemoryUsage();

        double fdDeltaToOpen = fileDescriptorsWhileConnectionsOpen
                - initialFileDescriptors;
        double fdDeltaToClosed = fileDescriptorsAfterConnectionsClosed
                - initialFileDescriptors;

        double fdDeltaRatio = fdDeltaToClosed / fdDeltaToOpen;
        assertThat(
                "Number of file descriptors after close should be much closer to initial value than number of file descriptors while open (+ 1%).\n"
                        + "Initial file descriptors: " + initialFileDescriptors + "; file descriptors while connections open: " + fileDescriptorsWhileConnectionsOpen + "; "
                        + "file descriptors after connections closed: " + fileDescriptorsAfterConnectionsClosed + "\n"
                        + "Ratio of file descriptors after connections are closed to descriptors before connections were closed: " + fdDeltaRatio,
                fdDeltaRatio, lessThan(0.01));
    }
}
