package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

/**
 * HTTP proxy server.
 */
public class HttpProxyServer {
    
    /**
     * Starts the proxy from the command line.
     * 
     * @param args Any command line arguments.
     */
    public static void main(final String[] args) {
        final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));

        bootstrap.setPipelineFactory(new HttpServerPipelineFactory());
        bootstrap.bind(new InetSocketAddress("127.0.0.1", 8080));
        
        /*
        final ServerBootstrap sslBootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));
        sslBootstrap.setPipelineFactory(new HttpServerPipelineFactory());
        sslBootstrap.bind(new InetSocketAddress("127.0.0.1", 8443));
        */
    }
}
