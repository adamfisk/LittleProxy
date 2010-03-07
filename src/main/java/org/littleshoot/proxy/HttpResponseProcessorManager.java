package org.littleshoot.proxy;

import java.util.Collection;

/**
 * Manager for response processors.
 */
public interface HttpResponseProcessorManager extends HttpResponseFilter{

    /**
     * Adds a response processor factory to the chain.
     * 
     * @param responseProcessorFactory The factory for creating processors.
     */
    void addResponseProcessor(
        HttpResponseFilter responseProcessor);

    Collection<HttpResponseFilterFactory> getAll();

    void addAll(Collection<HttpResponseFilterFactory> all);

}
