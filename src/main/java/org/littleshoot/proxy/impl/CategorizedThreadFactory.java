package org.littleshoot.proxy.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A ThreadFactory that adds LittleProxy-specific information to the threads' names.
 */
public class CategorizedThreadFactory implements ThreadFactory {
    private static final Logger log = LoggerFactory.getLogger(CategorizedThreadFactory.class);

    private final String name;
    private final String category;
    private final int uniqueServerGroupId;

    private AtomicInteger threadCount = new AtomicInteger(0);

    /**
     * Exception handler for proxy threads. Logs the name of the thread and the exception that was caught.
     */
    private static final Thread.UncaughtExceptionHandler UNCAUGHT_EXCEPTION_HANDLER = (t, e) -> log.error("Uncaught throwable in thread: {}", t.getName(), e);


    /**
     * @param name the user-supplied name of this proxy
     * @param category the type of threads this factory is creating (acceptor, client-to-proxy worker, proxy-to-server worker)
     * @param uniqueServerGroupId a unique number for the server group creating this thread factory, to differentiate multiple proxy instances with the same name
     */
    public CategorizedThreadFactory(String name, String category, int uniqueServerGroupId) {
        this.category = category;
        this.name = name;
        this.uniqueServerGroupId = uniqueServerGroupId;
    }

    @Override
    public Thread newThread(Runnable r) {
        Thread t = new Thread(r, name + "-" + uniqueServerGroupId + "-" + category + "-" + threadCount.getAndIncrement());

        t.setUncaughtExceptionHandler(UNCAUGHT_EXCEPTION_HANDLER);

        return t;
    }

}
