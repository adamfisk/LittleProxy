package org.littleshoot.proxy;

/**
 * Interface for factory classes that generate response processors.
 */
public interface HttpResponseProcessorFactory {

    /**
     * Creates a new response processor.
     * 
     * @return The new processor.
     */
    HttpResponseProcessor newProcessor();

}
