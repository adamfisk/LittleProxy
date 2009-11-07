package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP proxy server.
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
    
    private final Logger m_log = 
        LoggerFactory.getLogger(DefaultHttpProxyServer.class);
    
    private final ChannelGroup m_allChannels = 
        new DefaultChannelGroup("HTTP-Proxy-Server");
            
    private final int m_port;
    
    private final ProxyAuthorizationManager m_authenticationManager =
        new DefaultProxyAuthorizationManager();
    
    public DefaultHttpProxyServer(final int port) {
        this.m_port = port;
    }
    
    public void start() {
        m_log.info("Starting proxy on port: "+this.m_port);
        final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));

        final HttpServerPipelineFactory factory = 
            new HttpServerPipelineFactory(m_authenticationManager, this.m_allChannels);
        bootstrap.setPipelineFactory(factory);
        final Channel channel = bootstrap.bind(new InetSocketAddress(m_port));
        m_allChannels.add(channel);
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                final ChannelGroupFuture future = m_allChannels.close();
                future.awaitUninterruptibly();
                bootstrap.releaseExternalResources();
            }
        }));
        /*
        final ServerBootstrap sslBootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));
        sslBootstrap.setPipelineFactory(new HttpServerPipelineFactory());
        sslBootstrap.bind(new InetSocketAddress("127.0.0.1", 8443));
        */
    }

    public void addProxyAuthenticationHandler(
        final ProxyAuthorizationHandler pah) {
        this.m_authenticationManager.addHandler(pah);
    }
}
