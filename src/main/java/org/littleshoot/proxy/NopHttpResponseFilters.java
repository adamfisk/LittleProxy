package org.littleshoot.proxy;

import java.net.SocketAddress;

/**
 * An implementation of {@link HttpResponseFilters} that doesn't have any {@link HttpFilter}s.
 */
class NopHttpResponseFilters implements HttpResponseFilters {

    public static final NopHttpResponseFilters NO_RESPONSE_FILTERS = new NopHttpResponseFilters();
    
    @Override
    public HttpFilter getFilter(SocketAddress address) {
        return null;
    }
}
