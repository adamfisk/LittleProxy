package org.littleshoot.proxy;

/**
 * Manager for response processors.
 */
public interface HttpResponseProcessorManager extends HttpResponseProcessor{

    /**
     * Adds a response processor to the chain.
     * 
     * @param responseProcessor The processor.
     */
    void addResponseProcessor(HttpResponseProcessor responseProcessor);

}
