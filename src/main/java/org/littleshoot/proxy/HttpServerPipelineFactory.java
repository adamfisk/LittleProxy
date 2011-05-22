package org.littleshoot.proxy;

import static org.jboss.netty.channel.Channels.pipeline;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.net.ssl.SSLEngine;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.math.NumberUtils;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Factory for creating pipelines for incoming requests to our listening
 * socket.
 */
public class HttpServerPipelineFactory implements ChannelPipelineFactory, 
    AllConnectionData {
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    private static final boolean CACHE_ENABLED = false;
    
    private final ProxyAuthorizationManager authenticationManager;
    private final ChannelGroup channelGroup;
    private final Map<String, HttpFilter> filters;
    private final String chainProxyHostAndPort;
    
    private final ClientSocketChannelFactory clientSocketChannelFactory =
        new NioClientSocketChannelFactory(
            Executors.newCachedThreadPool(),
            Executors.newCachedThreadPool());
    private final ProxyCacheManager cacheManager;
    
    //private final GlobalTrafficShapingHandler trafficShaper;

    private final KeyStoreManager ksm;

    private final HttpRequestFilter requestFilter;

    private int numHandlers;

    private boolean useJmx;

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param authorizationManager The manager for proxy authentication.
     * @param channelGroup The group that keeps track of open channels.
     * @param filters HTTP filters to apply.
     * @param chainProxyHostAndPort upstream proxy server host and port or 
     * <code>null</code> if none used.
     * @param isSsl Whether or not to use SSL/TLS.
     */
    public HttpServerPipelineFactory(
        final ProxyAuthorizationManager authorizationManager, 
        final ChannelGroup channelGroup, 
        final Map<String, HttpFilter> filters,
        final String chainProxyHostAndPort, final KeyStoreManager ksm,
        final HttpRequestFilter requestFilter) {
        log.info("Creating server with keystore manager: {}", ksm);
        this.authenticationManager = authorizationManager;
        this.channelGroup = channelGroup;
        this.filters = filters;
        this.chainProxyHostAndPort = chainProxyHostAndPort;
        this.ksm = ksm;
        this.requestFilter = requestFilter;
        
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
        
        final Properties props = new Properties();
        final File propsFile = new File("./littleproxy.properties");
        
        long readThrottle = -1;
        long writeThrottle = -1;
        try {
            final InputStream is = new FileInputStream(propsFile);
            props.load(is);
            this.useJmx = extractBoolean(props, "jmx");
            if (this.useJmx) {
                setupJmx();
            }
            /*
            final boolean useThrottle = extractBoolean(props, "throttle");
            if (useThrottle) {
                readThrottle = extractLong(props, "readThrottle");
                writeThrottle = extractLong(props, "writeThrottle");
            }
            */
        } catch (final IOException e) {
            log.info("Not using props file");
            // No problem -- just don't use 'em.
        }

        if (readThrottle == -1 && writeThrottle == -1) {
            //trafficShaper = null;
        }
        else {
            log.info("Traffic shaping writes at {} bytes per second", 
                writeThrottle);
            log.info("Traffic shaping reads at {} bytes per second", 
                readThrottle);
            // Last arg in milliseconds.
            //trafficShaper = 
                //new GlobalTrafficShapingHandler(Executors.newCachedThreadPool(), 
                //    writeThrottle, readThrottle, 1000); 
        }
        
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                clientSocketChannelFactory.releaseExternalResources();
                /*
                if (trafficShaper != null) {
                    trafficShaper.releaseExternalResources();
                }
                */
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
    
    private boolean extractBoolean(final Properties props, final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return false;
    }

    private long extractLong(Properties props, String key) {
        final String readThrottleString = props.getProperty(key);
        if (StringUtils.isNotBlank(readThrottleString) &&
            NumberUtils.isNumber(readThrottleString)) {
            return Long.parseLong(readThrottleString);
        }
        return -1;
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
        pipeline.addLast("decoder", new HttpRequestDecoder());
        pipeline.addLast("encoder", new ProxyHttpResponseEncoder(cacheManager));

        /*
        if (trafficShaper != null) {
            pipeline.addLast("GLOBAL_TRAFFIC_SHAPING", trafficShaper);
        }
        */
        pipeline.addLast("handler", 
            new HttpRequestHandler(this.cacheManager, authenticationManager, 
                this.channelGroup, this.filters, 
                this.clientSocketChannelFactory,
                this.chainProxyHostAndPort, this.requestFilter, this.useJmx));
        this.numHandlers++;
        return pipeline;
    }

    public int getNumRequestHandlers() {
        return this.numHandlers;
    }
}
