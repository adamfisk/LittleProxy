package org.littleshoot.proxy;

/**
 * Interface for the top-level proxy server class.
 */
public interface HttpProxyServer {

    /**
     * Starts the server.
     */
    void start();

    /**
     * Stops the server.
     */
    void stop();

    /**
     * Starts the server.
     * 
     * @param localOnly
     *            If true, the server will only allow connections from the local
     *            computer. This can significantly improve security in some
     *            cases.
     * @param anyAddress
     *            Whether or not to bind to "any" address - 0.0.0.0. This is the
     *            default.
     */
    void start(boolean localOnly, boolean anyAddress);

    /**
     * Add an ActivityTracker for tracking proxying activity.
     * 
     * @param activityTracker
     * @return this HttpProxyServer for call chaining
     */
    HttpProxyServer addActivityTracker(ActivityTracker activityTracker);

}
