package org.littleshoot.proxy;

import java.util.Collection;

/**
 * Manager for response processors.
 */
public interface HttpResponseProcessorManager extends HttpResponseProcessor{

    /**
     * Adds a response processor factory to the chain.
     * 
     * @param responseProcessorFactory The factory for creating processors.
     */
    void addResponseProcessor(
        HttpResponseProcessor responseProcessor);

    Collection<HttpResponseProcessorFactory> getAll();

    void addAll(Collection<HttpResponseProcessorFactory> all);

}
