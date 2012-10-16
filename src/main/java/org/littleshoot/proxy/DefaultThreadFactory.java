package org.littleshoot.proxy;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A simple implementation of {@link ThreadFactory}
 */
class DefaultThreadFactory implements ThreadFactory {

    private final AtomicInteger counter = new AtomicInteger();
    
    private final String name;

    private final boolean daemon;
    
    public DefaultThreadFactory(String name) {
        this(name, false);
    }
    
    public DefaultThreadFactory(String name, boolean daemon) {
        this.name = name;
        this.daemon = daemon;
    }
    
    private String getNextName() {
        return name + "-" + counter.getAndIncrement();
    }
    
    @Override
    public Thread newThread(Runnable r) {
        Thread thread = new Thread(r, getNextName());
        thread.setDaemon(daemon);
        return thread;
    }
}
