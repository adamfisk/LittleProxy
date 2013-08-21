package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.net.InetSocketAddress;

/**
 * Interface for receiving callbacks about activity in the proxy.
 */
public interface ActivityTracker {
    /**
     * Record that bytes were received from the client.
     * 
     * @param clientAddress
     * @param serverHostAndPort
     * @param chainedProxyHostAndPort
     * @param numberOfBytes
     */
    void bytesReceivedFromClient(InetSocketAddress clientAddress, String serverHostAndPort,
            String chainedProxyHostAndPort, int numberOfBytes);

    /**
     * Record that a request was sent from client to server.
     * 
     * @param clientAddress
     * @param serverHostAndPort
     * @param chainedProxyHostAndPort
     * @param httpRequest
     */
    void requestSent(InetSocketAddress clientAddress, String serverHostAndPort,
            String chainedProxyHostAndPort, HttpRequest httpRequest);

    /**
     * Record that bytes were received from server the server.
     * 
     * @param clientAddress
     * @param serverHostAndPort
     * @param chainedProxyHostAndPort
     * @param numberOfBytes
     */
    void bytesReceivedFromServer(InetSocketAddress clientAddress,
            String serverHostAndPort,
            String chainedProxyHostAndPort, int numberOfBytes);

    /**
     * Record that a response was received from the server.
     * 
     * @param clientAddress
     * @param serverHostAndPort
     * @param chainedProxyHostAndPort
     * @param httpResponse
     */
    void responseReceived(InetSocketAddress clientAddress,
            String serverHostAndPort,
            String chainedProxyHostAndPort, HttpResponse httpResponse);

}
