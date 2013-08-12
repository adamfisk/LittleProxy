package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.timeout.IdleStateHandler;

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
    //private final KeyStoreManager ksm;
    
    private final HandshakeHandlerFactory handshakeHandlerFactory;
    private int numHandlers;
    private final RelayChannelInitializerFactory relayChannelInitializerFactory;
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
     * @param relayChannelInitializerFactory The relay channel initializer factory.
     * @param clientWorker The EventLoopGroup for creating outgoing channels
     *  to external sites.
     */
    public HttpServerChannelInitializer(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ChainProxyManager chainProxyManager, 
        final HandshakeHandlerFactory handshakeHandlerFactory,
        final RelayChannelInitializerFactory relayChannelInitializerFactory, 
        final EventLoopGroup clientWorker) {
        
        this.handshakeHandlerFactory = handshakeHandlerFactory;
        this.relayChannelInitializerFactory = relayChannelInitializerFactory;
        this.clientWorker = clientWorker;
        
        log.debug("Creating server with handshake handler: {}", 
                handshakeHandlerFactory);
        this.authenticationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.chainProxyManager = chainProxyManager;
        //this.ksm = ksm;
        
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
        pipeline.addLast("encoder", new ProxyHttpResponseEncoder());
        
        final HttpRequestHandler httpRequestHandler = 
            new HttpRequestHandler(authenticationManager,
            this.channelGroup, this.chainProxyManager, 
            relayChannelInitializerFactory, this.clientWorker);
        
        pipeline.addLast("idle", new IdleStateHandler(0, 0, 70));
        //pipeline.addLast("idleAware", new IdleAwareHandler("Client-Pipeline"));
        pipeline.addLast("idleAware", new IdleRequestHandler(httpRequestHandler));
        pipeline.addLast("handler", httpRequestHandler);
        this.numHandlers++;
    }

    @Override
    public int getNumRequestHandlers() {
        return this.numHandlers;
    }
}
