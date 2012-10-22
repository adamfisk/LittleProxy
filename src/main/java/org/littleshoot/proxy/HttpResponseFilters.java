package org.littleshoot.proxy;

import java.net.SocketAddress;


/**
 * Interface for accessing response filters.
 */
public interface HttpResponseFilters {

    /**
     * Returns a filter for the specified host and port of the initial request.
     * 
     * @param hostAndPort The host and port of the initial request
     * @return The {@link HttpFilter} for the request.
     */
    HttpFilter getFilter(SocketAddress address);
}
