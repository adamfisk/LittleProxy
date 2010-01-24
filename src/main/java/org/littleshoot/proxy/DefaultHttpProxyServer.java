package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.group.ChannelGroupFuture;
import org.jboss.netty.channel.group.DefaultChannelGroup;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * HTTP proxy server.
 */
public class DefaultHttpProxyServer implements HttpProxyServer {
    
    private final Logger log = 
        LoggerFactory.getLogger(DefaultHttpProxyServer.class);
    
    private final ChannelGroup allChannels = 
        new DefaultChannelGroup("HTTP-Proxy-Server");
            
    private final int port;
    
    private final ProxyAuthorizationManager authenticationManager =
        new DefaultProxyAuthorizationManager();

    private final DefaultHttpRelayingHandlerFactory handlerFactory;

    /**
     * Creates a new server with the default, no-op response processor.
     * 
     * @param port The port to listen on.
     */
    public DefaultHttpProxyServer(final int port) {
        this(port, new HttpResponseProcessorFactory() {
            public HttpResponseProcessor newProcessor() {
                return new HttpResponseProcessor() {
                    public HttpResponse processResponse(
                        final HttpResponse response, 
                        final String hostAndPort) {
                        return response;
                    }
                    
                    public HttpChunk processChunk(final HttpChunk chunk, 
                        final String hostAndPort) {
                        return chunk;
                    }
                };
            }
        });
    }
    
    /**
     * Creates a new proxy server.
     * 
     * @param port The port the server should run on.
     * @param responseProcessorFactory 
     */
    public DefaultHttpProxyServer(final int port, 
        final HttpResponseProcessorFactory responseProcessorFactory) {
        this.port = port;
        //this.responseProcessorManager = 
        //    new DefaultHttpResponseProcessorManager();
        this.handlerFactory = 
            new DefaultHttpRelayingHandlerFactory(this.allChannels, 
                responseProcessorFactory);
    }
    
    public void start() {
        log.info("Starting proxy on port: "+this.port);
        final ServerBootstrap bootstrap = new ServerBootstrap(
            new NioServerSocketChannelFactory(
                Executors.newCachedThreadPool(),
                Executors.newCachedThreadPool()));

        final HttpServerPipelineFactory factory = 
            new HttpServerPipelineFactory(authenticationManager, 
                this.handlerFactory, this.allChannels);
        bootstrap.setPipelineFactory(factory);
        final Channel channel = bootstrap.bind(new InetSocketAddress(port));
        allChannels.add(channel);
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                final ChannelGroupFuture future = allChannels.close();
                future.awaitUninterruptibly(120*1000);
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
        this.authenticationManager.addHandler(pah);
    }
}
