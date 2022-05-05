package org.littleshoot.proxy;

import javax.net.ssl.SSLEngine;

/**
 * Source for {@link SSLEngine}s.
 */
public interface SslEngineSource {

    /**
     * Returns an {@link SSLEngine} to use for a server connection from
     * LittleProxy to the client.
     */
    SSLEngine newSslEngine();

    /**
     * Returns an {@link SSLEngine} to use for a client connection from
     * LittleProxy to the upstream server. *
     * 
     * Note: Peer information is needed to send the server_name extension in
     * handshake with Server Name Indication (SNI).
     * 
     * @param peerHost
     *            to start a client connection to the server.
     * @param peerPort
     *            to start a client connection to the server.
     */
    SSLEngine newSslEngine(String peerHost, int peerPort);

}
