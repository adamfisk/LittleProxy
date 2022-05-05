package org.littleshoot.proxy;

import java.net.InetSocketAddress;

/**
 * Interface for the top-level proxy server class.
 */
public interface HttpProxyServer {

    int getIdleConnectionTimeout();

    void setIdleConnectionTimeout(int idleConnectionTimeout);

    /**
     * Returns the maximum time to wait, in milliseconds, to connect to a server.
     */
    int getConnectTimeout();

    /**
     * Sets the maximum time to wait, in milliseconds, to connect to a server.
     */
    void setConnectTimeout(int connectTimeoutMs);

    /**
     * <p>
     * Clone the existing server, with a port 1 higher and everything else the
     * same. If the proxy was started with port 0 (JVM-assigned port), the cloned proxy will also use a JVM-assigned
     * port.
     * </p>
     * 
     * <p>
     * The new server will share event loops with the original server. The event
     * loops will use whatever name was given to the first server in the clone
     * group. The server group will not terminate until the original server and all clones terminate.
     * </p>
     * 
     * @return a bootstrap that allows customizing and starting the cloned
     *         server
     */
    HttpProxyServerBootstrap clone();

    /**
     * Stops the server and all related clones. Waits for traffic to stop before shutting down.
     */
    void stop();

    /**
     * Stops the server and all related clones immediately, without waiting for traffic to stop.
     */
    void abort();

    /**
     * Return the address on which this proxy is listening.
     */
    InetSocketAddress getListenAddress();

    /**
     * <p>
     * Set the read/write throttle bandwidths (in bytes/second) for this proxy.
     * </p>
     */
    void setThrottle(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond);
}
