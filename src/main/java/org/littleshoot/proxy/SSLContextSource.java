package org.littleshoot.proxy;

import javax.net.ssl.SSLContext;

/**
 * Source for {@link SSLContext}s.
 */
public interface SSLContextSource {
    SSLContext getSSLContext();
}
