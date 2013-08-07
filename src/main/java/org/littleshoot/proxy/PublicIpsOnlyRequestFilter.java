package org.littleshoot.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;

import io.netty.handler.codec.http.HttpRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Request filter that only allows requests for public IPs.
 */
public class PublicIpsOnlyRequestFilter implements HttpRequestFilter {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    public void filter(final HttpRequest request) {
        final String host = ProxyUtils.parseHost(request);
        try {
            final InetAddress ia = InetAddress.getByName(host);
            if (NetworkUtils.isPublicAddress(ia)) {
                log.info("Allowing request for public address");
                return;
            } else {
                // We do this for security reasons -- we don't
                // want to allow proxies to inadvertantly expose
                // internal network services.
                log.warn("Request for non-public resource: {} \n full request: {}",
                    request.getUri(), request);
                throw new UnsupportedOperationException(
                    "Not a public address: "+ia);
            }
        } catch (final UnknownHostException uhe) {
            throw new UnsupportedOperationException(
                "Could not resolve host", uhe);
        }
    }

}
