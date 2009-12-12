package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import javax.net.ssl.SSLEngine;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.ssl.SslHandler;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpsServerPipelineFactory implements ChannelPipelineFactory {
    
    private final ProxyAuthorizationManager m_authenticationManager;
    private final ChannelGroup m_channelGroup;

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     */
    public HttpsServerPipelineFactory(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup) {
        this.m_authenticationManager = authorizationManager;
        this.m_channelGroup = channelGroup;
    }

    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = pipeline();

        // Uncomment the following line if you want HTTPS
        /*
        final SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
        engine.setUseClientMode(false);
        pipeline.addLast("ssl", new SslHandler(engine));
        
        // We want to allow longer request lines, headers, and chunks respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("handler", 
            new HttpRequestHandler(m_authenticationManager, this.m_channelGroup));
        return pipeline;
        */
        return null;
    }
}
