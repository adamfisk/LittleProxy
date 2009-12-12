package org.littleshoot.proxy;

import java.util.Collection;
import java.util.LinkedList;

import org.jboss.netty.handler.codec.http.HttpResponse;

/**
 * Class that manages HTTP response processors.
 */
public class DefaultHttpResponseProcessorManager implements
    HttpResponseProcessorManager {
    
    private final Collection<HttpResponseProcessor> responseProcessors =
        new LinkedList<HttpResponseProcessor>();

    public void addResponseProcessor(final HttpResponseProcessor responseProcessor) {
        this.responseProcessors.add(responseProcessor);
    }

    public HttpResponse processResponse(final HttpResponse response) {
        System.out.println("PROCESSING..");
        HttpResponse processed = response;
        for (final HttpResponseProcessor rp : responseProcessors) {
            processed = rp.processResponse(processed);
        }
        return processed;
    }
}
