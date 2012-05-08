package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.net.ssl.SSLEngine;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.group.ChannelGroup;
import org.jboss.netty.channel.socket.ClientSocketChannelFactory;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpRequestDecoder;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.ssl.SslHandler;
import org.jboss.netty.handler.timeout.IdleStateHandler;
import org.jboss.netty.util.HashedWheelTimer;
import org.jboss.netty.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpServerPipelineFactory implements ChannelPipelineFactory, 
    AllConnectionData {
    
    private static final Logger log = 
        LoggerFactory.getLogger(HttpServerPipelineFactory.class);
    
    private static final boolean CACHE_ENABLED = false;
    
    private final ProxyAuthorizationManager authenticationManager;
    private final ChannelGroup channelGroup;
    private final ChainProxyManager chainProxyManager;
    
    private final ClientSocketChannelFactory clientSocketChannelFactory =
        new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    private final ProxyCacheManager cacheManager;
    
    //private final GlobalTrafficShapingHandler trafficShaper;

    private final KeyStoreManager ksm;

    private int numHandlers;

    //private boolean useJmx;
    
    private final RelayPipelineFactoryFactory relayPipelineFactoryFactory;
    
    private static final Timer TIMER = new HashedWheelTimer();

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     * @param filters HTTP filters to apply.
     * @param chainProxyManager upstream proxy server host and port or 
     * <code>null</code> if none used.
     * @param isSsl Whether or not to use SSL/TLS.
     */
    public HttpServerPipelineFactory(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final ChainProxyManager chainProxyManager, final KeyStoreManager ksm,
        final RelayPipelineFactoryFactory relayPipelineFactoryFactory) {
    	
    	this.relayPipelineFactoryFactory = relayPipelineFactoryFactory;
    	
        log.info("Creating server with keystore manager: {}", ksm);
        this.authenticationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.chainProxyManager = chainProxyManager;
        this.ksm = ksm;
        
        if (CACHE_ENABLED) {
            cacheManager = new DefaultProxyCacheManager();
        } else {
            cacheManager = new ProxyCacheManager() {
                
                public boolean returnCacheHit(final HttpRequest request, 
                    final Channel channel) {
                    return false;
                }
                
                public Future<String> cache(final HttpRequest originalRequest,
                    final HttpResponse httpResponse, 
                    final Object response, final ChannelBuffer encoded) {
                    return null;
                }
            };
        }
        
        if (LittleProxyConfig.isUseJmx()) {
            setupJmx();
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                clientSocketChannelFactory.releaseExternalResources();
            }
        }));
    }
    
    private void setupJmx() {
        final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            final Class<? extends AllConnectionData> clazz = getClass();
            final String pack = clazz.getPackage().getName();
            final String oName =
                pack+":type="+clazz.getSimpleName()+"-"+clazz.getSimpleName() + 
                hashCode();
            log.info("Registering MBean with name: {}", oName);
            final ObjectName mxBeanName = new ObjectName(oName);
            if(!mbs.isRegistered(mxBeanName)) {
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
    
    public ChannelPipeline getPipeline() throws Exception {
        final ChannelPipeline pipeline = pipeline();

        log.info("Accessing pipeline");
        if (this.ksm != null) {
            log.info("Adding SSL handler");
            final SslContextFactory scf = new SslContextFactory(this.ksm);
            final SSLEngine engine = scf.getServerContext().createSSLEngine();
            engine.setUseClientMode(false);
            pipeline.addLast("ssl", new SslHandler(engine));
        }
            
        // We want to allow longer request lines, headers, and chunks 
        // respectively.
        pipeline.addLast("decoder", 
            new HttpRequestDecoder(8192, 8192*2, 8192*2));
        pipeline.addLast("encoder", new ProxyHttpResponseEncoder(cacheManager));
        
        
		/*
        if (trafficShaper != null) {
            pipeline.addLast("GLOBAL_TRAFFIC_SHAPING", trafficShaper);
        }
        */

        final HttpRequestHandler httpRequestHandler = 
            new HttpRequestHandler(this.cacheManager, authenticationManager,
            this.channelGroup, this.clientSocketChannelFactory,
            this.chainProxyManager, relayPipelineFactoryFactory);
        
        pipeline.addLast("idle", new IdleStateHandler(TIMER, 0, 0, 70));
        //pipeline.addLast("idleAware", new IdleAwareHandler("Client-Pipeline"));
        pipeline.addLast("idleAware", new IdleRequestHandler(httpRequestHandler));
        pipeline.addLast("handler", httpRequestHandler);
        this.numHandlers++;
        return pipeline;
    }

    public int getNumRequestHandlers() {
        return this.numHandlers;
    }
}
