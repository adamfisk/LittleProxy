package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpServerPipelineFactory implements ChannelPipelineFactory {
    
    private final ProxyAuthorizationManager m_authenticationManager;

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authenticationManager The manager for proxy authentication.
     */
    public HttpServerPipelineFactory(
        final ProxyAuthorizationManager authenticationManager) {
        this.m_authenticationManager = authenticationManager;
    }

    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = pipeline();

        // Uncomment the following line if you want HTTPS
        //SSLEngine engine = SecureChatSslContextFactory.getServerContext().createSSLEngine();
        //engine.setUseClientMode(false);
        //pipeline.addLast("ssl", new SslHandler(engine));
        
        // We want to allow longer request lines, headers, and chunks respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder(8192*2, 8192*2, 8192*2));
        pipeline.addLast("encoder", new HttpResponseEncoder());
        pipeline.addLast("handler", new HttpRequestHandler(m_authenticationManager));
        return pipeline;
    }
}
