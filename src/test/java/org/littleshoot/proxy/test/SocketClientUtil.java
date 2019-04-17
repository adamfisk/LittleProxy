package org.littleshoot.proxy.test;

import org.littleshoot.proxy.HttpProxyServer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;

/**
 * Utilities for interacting with the proxy server using sockets.
 */
public class SocketClientUtil {
    /**
     * Writes and flushes the UTF-8 encoded contents of a String to a socket.
     *
     * @param string string to write
     * @param socket socket to write to
     * @throws IOException
     */
    public static void writeStringToSocket(String string, Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        out.write(string.getBytes(Charset.forName("UTF-8")));
        out.flush();
    }

    /**
     * Reads all available data from the socket and returns a String containing that content, interpreted in the
     * UTF-8 charset.
     *
     * @param socket socket to read UTF-8 bytes from
     * @return String containing the contents of whatever was read from the socket
     * @throws EOFException if the socket has been closed
     */
    public static String readStringFromSocket(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        byte[] bytes = new byte[10000];
        int bytesRead = in.read(bytes);
        if (bytesRead == -1) {
            throw new EOFException("Unable to read from socket. The socket is closed.");
        }

        return new String(bytes, 0, bytesRead, Charset.forName("UTF-8"));
    }

    /**
     * Determines if the socket can be written to. This method tests the writability of the socket by writing to the socket,
     * so it should only be used immediately before closing the socket.
     *
     * @param socket socket to test
     * @return true if the socket is open and can be written to, otherwise false
     * @throws IOException
     */
    public static boolean isSocketReadyToWrite(Socket socket) throws IOException {
        OutputStream out = socket.getOutputStream();
        try {
            for(int i = 0; i < 500; ++i) {
                out.write(0);
                out.flush();
            }
        } catch (SocketException e) {
            return false;
        }

        return true;
    }

    /**
     * Determines if the socket can be read from. This method tests the readability of the socket by attempting to read
     * a byte from the socket. If successful, the byte will be lost, so this method should only be called immediately
     * before closing the socket.
     *
     * @param socket socket to test
     * @return true if the socket is open and can be read from, otherwise false
     * @throws IOException
     */
    public static boolean isSocketReadyToRead(Socket socket) throws IOException {
        InputStream in = socket.getInputStream();
        try {
            int readByte = in.read();

            // we just lost that byte but it doesn't really matter for testing purposes
            return readByte != -1;
        } catch (SocketException e) {
            // the socket couldn't be read, perhaps because the connection was reset or some other error. it cannot be read.
          return false;
        } catch (SocketTimeoutException e) {
            // the read timed out, which means the socket is still connected but there's no data on it
            return true;
        }
    }

    /**
     * Opens a socket to the specified proxy server with a 3s timeout. The socket should be closed after it has been used.
     *
     * @param proxyServer proxy server to open the socket to
     * @return the new socket
     * @throws IOException
     */
    public static Socket getSocketToProxyServer(HttpProxyServer proxyServer) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", proxyServer.getListenAddress().getPort()), 1000);
        socket.setSoTimeout(3000);
        return socket;
    }
}
