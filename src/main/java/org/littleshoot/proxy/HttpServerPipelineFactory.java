package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpServerPipelineFactory implements ChannelPipelineFactory {
    
    private final ProxyAuthorizationManager m_authenticationManager;
    private final ChannelGroup m_channelGroup;
    private final HttpRelayingHandlerFactory m_handlerFactory;

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param handlerFactory The class that creates new relaying handles for
     * relaying responses back to the client.
     * @param channelGroup The group that keeps track of open channels.
     */
    public HttpServerPipelineFactory(
        final ProxyAuthorizationManager authorizationManager, 
        final HttpRelayingHandlerFactory handlerFactory, 
        final ChannelGroup channelGroup) {
        this.m_authenticationManager = authorizationManager;
        this.m_handlerFactory = handlerFactory;
        this.m_channelGroup = channelGroup;
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
            new HttpRequestHandler(m_authenticationManager, 
                this.m_handlerFactory, this.m_channelGroup));
        return pipeline;
    }
}
