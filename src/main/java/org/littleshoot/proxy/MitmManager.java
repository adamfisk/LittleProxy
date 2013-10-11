package org.littleshoot.proxy;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * MITMManagers encapsulate the logic required for letting LittleProxy act as a
 * man in the middle for HTTPS requests.
 */
public interface MitmManager {
    /**
     * Creates an {@link SSLEngine} for encrypting the server connection.
     * 
     * @return
     */
    SSLEngine serverSslEngine();

    /**
     * <p>
     * Creates an {@link SSLEngine} for encrypting the client connection based
     * on the given serverSslSession.
     * </p>
     * 
     * <p>
     * The serverSslSession is provided in case this method needs to inspect the
     * server's certificates or something else about the encryption on the way
     * to the server.
     * </p>
     * 
     * <p>
     * This is the place where one would implement impersonation of the server
     * by issuing replacement certificates signed by the proxy's own
     * certificate.
     * </p>
     * 
     * @param serverSslSession
     *            the {@link SSLSession} that's been established with the server
     * @return
     */
    SSLEngine clientSslEngineFor(SSLSession serverSslSession);
}
