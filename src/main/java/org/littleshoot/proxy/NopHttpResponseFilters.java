package org.littleshoot.proxy;

/**
 * An implementation of {@link HttpResponseFilters} that doesn't have any {@link HttpFilter}s.
 */
class NopHttpResponseFilters implements HttpResponseFilters {

    public static final NopHttpResponseFilters NO_RESPONSE_FILTERS = new NopHttpResponseFilters();
    
    @Override
    public HttpFilter getFilter(String hostAndPort) {
        return null;
    }
}
