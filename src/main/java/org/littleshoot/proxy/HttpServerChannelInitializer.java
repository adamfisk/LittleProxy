package org.littleshoot.proxy;

import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;

import java.lang.management.ManagementFactory;

import javax.management.InstanceAlreadyExistsException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;

import org.littleshoot.proxy.newstyle.ClientToProxyConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Initializes pipelines for incoming requests to our listening socket.
 */
public class HttpServerChannelInitializer extends ChannelInitializer<Channel>
        implements AllConnectionData {

    private final Logger log = LoggerFactory
            .getLogger(HttpServerChannelInitializer.class);

    private final ChannelGroup channelGroup;
    private final ChainProxyManager chainProxyManager;
    private final ProxyAuthenticator authenticator;

    private final HandshakeHandlerFactory handshakeHandlerFactory;
    private int numHandlers;
    private final EventLoopGroup clientWorker;

    /**
     * Creates a new pipeline factory with the specified class for processing
     * proxy authentication.
     * 
     * @param channelGroup
     *            The group that keeps track of open channels.
     * @param chainProxyManager
     *            upstream proxy server host and port or <code>null</code> if
     *            none used.
     * @param ksm
     *            The KeyStore manager.
     * @param clientWorker
     *            The EventLoopGroup for creating outgoing channels to external
     *            sites.
     * @param authenticator
     *            (optional) ProxyAuthenticator for this proxy
     */
    public HttpServerChannelInitializer(final ChannelGroup channelGroup,
            final ChainProxyManager chainProxyManager,
            final HandshakeHandlerFactory handshakeHandlerFactory,
            final EventLoopGroup clientWorker,
            final ProxyAuthenticator authenticator) {

        this.handshakeHandlerFactory = handshakeHandlerFactory;
        this.clientWorker = clientWorker;

        log.debug("Creating server with handshake handler: {}",
                handshakeHandlerFactory);
        this.authenticator = authenticator;
        this.channelGroup = channelGroup;
        this.chainProxyManager = chainProxyManager;

        if (LittleProxyConfig.isUseJmx()) {
            setupJmx();
        }
    }

    private void setupJmx() {
        final MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try {
            final Class<? extends AllConnectionData> clazz = getClass();
            final String pack = clazz.getPackage().getName();
            final String oName = pack + ":type=" + clazz.getSimpleName() + "-"
                    + clazz.getSimpleName() + hashCode();
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
            final HandshakeHandler hh = this.handshakeHandlerFactory
                    .newHandshakeHandler();
            pipeline.addLast(hh.getId(), hh.getChannelHandler());
        }

        // We want to allow longer request lines, headers, and chunks
        // respectively.
        pipeline.addLast("decoder", new HttpRequestDecoder(8192, 8192 * 2,
                8192 * 2));
        pipeline.addLast("encoder", new HttpResponseEncoder());

        final ClientToProxyConnection clientToProxyConnection = new ClientToProxyConnection(
                clientWorker, channelGroup, chainProxyManager, authenticator);

        // pipeline.addLast("idle", new IdleStateHandler(0, 0, 70));
        pipeline.addLast("handler", clientToProxyConnection);
        this.numHandlers++;
    }

    @Override
    public int getNumRequestHandlers() {
        return this.numHandlers;
    }
}
