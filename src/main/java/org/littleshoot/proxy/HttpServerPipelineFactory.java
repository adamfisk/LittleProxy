package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.util.Map;
import java.util.concurrent.Executors;

import net.sf.ehcache.CacheManager;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpServerPipelineFactory implements ChannelPipelineFactory {
    
    private final ProxyAuthorizationManager authenticationManager;
    private final ChannelGroup channelGroup;
    private final Map<String, HttpFilter> filters;
    
    private final ClientSocketChannelFactory clientSocketChannelFactory =
        new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    private final ProxyCacheManager cacheManager = 
        new DefaultProxyCacheManager();

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     * @param filters HTTP filters to apply.
     */
    public HttpServerPipelineFactory(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters) {
        this.authenticationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.filters = filters;
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                clientSocketChannelFactory.releaseExternalResources();
            }
        }));
    }

    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = pipeline();

        // Uncomment the following line if you want HTTPS
        //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
        //engine.setUseClientMode(false);
        //pipeline.addLast("ssl", new SslHandler(engine));
        
        // We want to allow longer request lines, headers, and chunks respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("handler", 
            new HttpRequestHandler(this.cacheManager, authenticationManager, 
                this.channelGroup, this.filters, 
                this.clientSocketChannelFactory));
        return pipeline;
    }
}
