package org.littleshoot.proxy;

/**
 * Interface for factory classes that generate response filters.
 */
public interface HttpResponseFilterFactory {

    /**
     * Creates a new response filter.
     * 
     * @return The new filter.
     */
    HttpResponseFilter newFilter();

}
