package org.littleshoot.proxy;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;

import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Note - this test only works on UNIX systems because it checks file descriptor
 * counts.
 */
public class IdleTest {
    private static final int NUMBER_OF_CONNECTIONS_TO_OPEN = 2000;
    private static final int WEB_SERVER_PORT = 9091;
    private static final int PROXY_PORT = 9091;

    private int originalIdleTimeout;
    private Server webServer;
    private DefaultHttpProxyServer proxyServer;

    @Before
    public void setup() throws Exception {
        webServer = new Server(WEB_SERVER_PORT);
        webServer.start();
        proxyServer = (DefaultHttpProxyServer) TestUtils
                .startProxyServer(PROXY_PORT);
        originalIdleTimeout = proxyServer.getIdleConnectionTimeout();
        proxyServer.setIdleConnectionTimeout(10);

    }

    @After
    public void tearDown() throws Exception {
        try {
            webServer.stop();
        } finally {
            proxyServer.stop();
        }
        proxyServer.setIdleConnectionTimeout(originalIdleTimeout);
    }

    @Test
    public void test() throws Exception {
        System.out
                .println("------------------ Memory Usage At Beginning ------------------");
        long initialFileDescriptors = getOpenFileDescriptorsAndPrintMemoryUsage();
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(
                "127.0.0.1", PROXY_PORT));
        for (int i = 0; i < NUMBER_OF_CONNECTIONS_TO_OPEN; i++) {
            new URL("http://localhost:" + WEB_SERVER_PORT)
                    .openConnection(proxy).connect();
        }

        System.gc();
        System.out
                .println("\n\n------------------ Memory Usage Before Idle Timeout ------------------");

        long fileDescriptorsWhileConnectionsOpen = getOpenFileDescriptorsAndPrintMemoryUsage();
        Thread.sleep(10000);

        System.gc();
        System.out
                .println("\n\n------------------ Memory Usage After Idle Timeout ------------------");
        long fileDescriptorsAfterConnectionsClosed = getOpenFileDescriptorsAndPrintMemoryUsage();

        double fdDeltaToOpen = fileDescriptorsWhileConnectionsOpen
                - initialFileDescriptors;
        double fdDeltaToClosed = fileDescriptorsAfterConnectionsClosed
                - initialFileDescriptors;

        double fdDeltaRatio = Math.abs(fdDeltaToClosed / fdDeltaToOpen);
        Assert.assertTrue(
                "Number of file descriptors after close should be much closer to initial value than number of file descriptors while open",
                fdDeltaRatio < 0.01);
    }

    private long getOpenFileDescriptorsAndPrintMemoryUsage() throws Exception {
        // Below courtesy of:
        // http://stackoverflow.com/questions/10999076/programmatically-print-the-heap-usage-that-is-typically-printed-on-jvm-exit-when
        MemoryUsage mu = ManagementFactory.getMemoryMXBean()
                .getHeapMemoryUsage();
        MemoryUsage muNH = ManagementFactory.getMemoryMXBean()
                .getNonHeapMemoryUsage();
        System.out.println("Init :" + mu.getInit() + "\nMax :" + mu.getMax()
                + "\nUsed :" + mu.getUsed() + "\nCommited :"
                + mu.getCommitted() + "\nInit NH :" + muNH.getInit()
                + "\nMax NH :" + muNH.getMax() + "\nUsed NH:" + muNH.getUsed()
                + "\nCommited NH:" + muNH.getCommitted());

        // Below courtesy of:
        // http://neopatel.blogspot.com/2011/05/java-count-open-file-handles.html
        OperatingSystemMXBean osStats = ManagementFactory
                .getOperatingSystemMXBean();
        long numberOfOpenFileDescriptors = 0;
        if (osStats.getClass().getName()
                .equals("com.sun.management.UnixOperatingSystem")) {
            Method method = osStats.getClass().getDeclaredMethod(
                    "getOpenFileDescriptorCount");
            method.setAccessible(true);
            numberOfOpenFileDescriptors = (Long) method.invoke(osStats);
            System.out.println("Open File Descriptors: "
                    + numberOfOpenFileDescriptors);
            method.setAccessible(false);
        }
        return numberOfOpenFileDescriptors;
    }
}
