package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.Timer;

import java.lang.management.ManagementFactory;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes pipelines for incoming requests to our listening socket.
 */
public class HttpServerChannelInitializer extends ChannelInitializer<Channel> implements
    AllConnectionData {
    
    private final Logger log = 
        LoggerFactory.getLogger(HttpServerChannelInitializer.class);
    
    private final ProxyAuthorizationManager authenticationManager;
    private final ChannelGroup channelGroup;
    private final ChainProxyManager chainProxyManager;
    private final ProxyCacheManager cacheManager;
    //private final KeyStoreManager ksm;
    
    private final HandshakeHandlerFactory handshakeHandlerFactory;
    private int numHandlers;
    private final RelayPipelineFactoryFactory relayPipelineFactoryFactory;
    private final Timer timer;
    private final EventLoopGroup clientWorker;
    
    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     * @param chainProxyManager upstream proxy server host and port or
     * <code>null</code> if none used.
     * @param ksm The KeyStore manager.
     * @param relayPipelineFactoryFactory The relay pipeline factory factory.
     * @param timer The global timer for timing out idle connections. 
     * @param clientWorker The EventLoopGroup for creating outgoing channels
     *  to external sites.
     */
    public HttpServerChannelInitializer(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ChainProxyManager chainProxyManager, 
        final HandshakeHandlerFactory handshakeHandlerFactory,
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory, 
        final Timer timer, final EventLoopGroup clientWorker) {
        this(authorizationManager, channelGroup, chainProxyManager, 
                handshakeHandlerFactory, 
                relayPipelineFactoryFactory, timer, clientWorker, 
                ProxyUtils.loadCacheManager());
    }
    
    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     * @param chainProxyManager upstream proxy server host and port or
     * <code>null</code> if none used.
     * @param ksm The KeyStore manager.
     * @param relayPipelineFactoryFactory The relay pipeline factory factory.
     * @param timer The global timer for timing out idle connections. 
     * @param clientWorker The EventLoopGroup for creating outgoing channels
     *  to external sites.
     */
    public HttpServerChannelInitializer(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ChainProxyManager chainProxyManager, 
        final HandshakeHandlerFactory handshakeHandlerFactory,
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory, 
        final Timer timer, final EventLoopGroup clientWorker,
        final ProxyCacheManager proxyCacheManager) {
        
        this.handshakeHandlerFactory = handshakeHandlerFactory;
        this.relayPipelineFactoryFactory = relayPipelineFactoryFactory;
        this.timer = timer;
        this.clientWorker = clientWorker;
        
        log.debug("Creating server with handshake handler: {}", 
                handshakeHandlerFactory);
        this.authenticationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.chainProxyManager = chainProxyManager;
        //this.ksm = ksm;
        this.cacheManager = proxyCacheManager;
        
        if (LittleProxyConfig.isUseJmx()) {
            setupJmx();
        }
    }
    
    private void setupJmx() {
        final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            final Class<? extends AllConnectionData> clazz = getClass();
            final String pack = clazz.getPackage().getName();
            final String oName =
                pack+":type="+clazz.getSimpleName()+"-"+clazz.getSimpleName() + 
                hashCode();
            log.debug("Registering MBean with name: {}", oName);
            final ObjectName mxBeanName = new ObjectName(oName);
            if (!mbs.isRegistered(mxBeanName)) {
                mbs.registerMBean(this, mxBeanName);
            }
        } catch (final MalformedObjectNameException e) {
            log.error("Could not set up JMX", e);
        } catch (final InstanceAlreadyExistsException e) {
            log.error("Could not set up JMX", e);
        } catch (final MBeanRegistrationException e) {
            log.error("Could not set up JMX", e);
        } catch (final NotCompliantMBeanException e) {
            log.error("Could not set up JMX", e);
        }
    }
    
    @Override
    protected void initChannel(Channel ch) throws Exception {
        final ChannelPipeline pipeline = ch.pipeline();

        log.debug("Accessing pipeline");
        if (this.handshakeHandlerFactory != null) {
            log.debug("Adding SSL handler");
            //final SslContextFactory scf = new SslContextFactory(this.ksm);
            //final SSLEngine engine = scf.getServerContext().createSSLEngine();
            //engine.setUseClientMode(false);
            //pipeline.addLast("ssl", new SslHandler(engine));
            final HandshakeHandler hh = 
                this.handshakeHandlerFactory.newHandshakeHandler();
            pipeline.addLast(hh.getId(), hh.getChannelHandler());
        }
            
        // We want to allow longer request lines, headers, and chunks 
        // respectively.
        pipeline.addLast("decoder", 
            new HttpRequestDecoder(8192, 8192*2, 8192*2));
        pipeline.addLast("encoder", new ProxyHttpResponseEncoder(cacheManager));
        
        final HttpRequestHandler httpRequestHandler = 
            new HttpRequestHandler(this.cacheManager, authenticationManager,
            this.channelGroup, this.chainProxyManager, 
            relayPipelineFactoryFactory, this.clientWorker);
        
        pipeline.addLast("idle", new IdleStateHandler(0, 0, 70));
        //pipeline.addLast("idleAware", new IdleAwareHandler("Client-Pipeline"));
        pipeline.addLast("idleAware", new IdleRequestHandler(httpRequestHandler));
        pipeline.addLast("handler", httpRequestHandler);
        this.numHandlers++;
        return pipeline;
    }

    @Override
    public int getNumRequestHandlers() {
        return this.numHandlers;
    }
}
