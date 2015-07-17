package org.littleshoot.proxy;

import java.net.InetSocketAddress;

/**
 * Interface for the top-level proxy server class.
 */
public interface HttpProxyServer {

    int getIdleConnectionTimeout();

    void setIdleConnectionTimeout(int idleConnectionTimeout);

    /**
     * <p>
     * Clone the existing server, with a port 1 higher and everything else the
     * same.
     * </p>
     * 
     * <p>
     * The new server will share event loops with the original server. The event
     * loops will use whatever name was given to the first server in the clone
     * group.
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
     * 
     * @return
     */
    InetSocketAddress getListenAddress();

    /**
     * <p>
     * Set the read/write throttle bandwidths (in bytes/second) for this proxy.
     * </p>
     * @param readThrottleBytesPerSecond
     * @param writeThrottleBytesPerSecond
     */
    void setThrottle(long readThrottleBytesPerSecond, long writeThrottleBytesPerSecond);
}
