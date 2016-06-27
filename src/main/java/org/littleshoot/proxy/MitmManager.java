package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;

/**
 * MITMManagers encapsulate the logic required for letting LittleProxy act as a
 * man in the middle for HTTPS requests.
 */
public interface MitmManager {
    /**
     * Creates an {@link SSLEngine} for encrypting the server connection. The SSLEngine created by this method
     * may use the given peer information to send SNI information when connecting to the upstream host.
     *
     * @param peerHost to start a client connection to the server.
     * @param peerPort to start a client connection to the server.
     * 
     * @return an SSLEngine used to connect to an upstream server
     */
    SSLEngine serverSslEngine(String peerHost, int peerPort);

    /**
     * Creates an {@link SSLEngine} for encrypting the server connection.
     *
     * @return an SSLEngine used to connect to an upstream server
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
     * @param httpRequest the HTTP CONNECT request that is being man-in-the-middled
     * @param serverSslSession the {@link SSLSession} that's been established with the server
     * @return the SSLEngine used to connect to the client
     */
    SSLEngine clientSslEngineFor(HttpRequest httpRequest, SSLSession serverSslSession);
}
