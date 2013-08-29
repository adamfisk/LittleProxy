package org.littleshoot.proxy;

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
     * The new server will share event loops with the original server.
     * </p>
     * 
     * @return a bootstrap that allows customizing and starting the cloned
     *         server
     */
    HttpProxyServerBootstrap clone();

    /**
     * Stops the server and all related clones.
     */
    void stop();

    /**
     * Add an ActivityTracker for tracking proxying activity.
     * 
     * @param activityTracker
     * @return this HttpProxyServer for call chaining
     */
    HttpProxyServer addActivityTracker(ActivityTracker activityTracker);

}
