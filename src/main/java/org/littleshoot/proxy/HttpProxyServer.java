package org.littleshoot.proxy;

/**
 * Interface for the top-level proxy server class.
 */
public interface HttpProxyServer {

    /**
     * Stops the server.
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
