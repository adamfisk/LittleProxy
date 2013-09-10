package org.littleshoot.proxy;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

/**
 * Source for {@link SSLContext}s.
 */
public interface SslEngineSource {
    SSLEngine newSslEngine();
}
