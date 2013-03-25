package org.littleshoot.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple class for storing configuration. We cheat here and make
 * this all static to avoid the overhead of integrating dependency 
 * injection that could collide with versions of libraries programs including
 * this library are using.
 */
public class LittleProxyConfig {

    private static final Logger LOG =
        LoggerFactory.getLogger(LittleProxyConfig.class);

    private static final Properties props = new Properties();

    static {

        final File propsFile = new File("./littleproxy.properties");

        if (propsFile.isFile()) {
            InputStream is = null;
            try {
                is = new FileInputStream(propsFile);
                props.load(is);
            } catch (final IOException e) {
                LOG.warn("Could not load props file?", e);
            } finally {
                IOUtils.closeQuietly(is);
            }
        }
    }


    private static boolean useDnsSec =
        ProxyUtils.extractBooleanDefaultFalse(props, "dnssec");

    private static boolean useJmx =
        ProxyUtils.extractBooleanDefaultFalse(props, "jmx");

    private static String proxyCacheManagerClass =
        props.getProperty("proxy_cache_manager_class");

    private static boolean useMITMInSSL =
            ProxyUtils.extractBooleanDefaultTrue(props, "use_ssl_mitm");

    private static boolean acceptAllSSLCertificates =
            ProxyUtils.extractBooleanDefaultFalse(props, "accept_all_ssl_certificates");

    private static boolean transparent =
            ProxyUtils.extractBooleanDefaultFalse(props, "transparent");

    private LittleProxyConfig(){}

    /**
     * Sets whether or not to use DNSSEC to request signed records when
     * performing DNS lookups and verifying those records if they exist.
     *
     * @param useDnsSec Whether or not to use DNSSEC.
     */
    public static void setUseDnsSec(final boolean useDnsSec) {
        LittleProxyConfig.useDnsSec = useDnsSec;
    }

    /**
     * Sets whether or not to use Man In The Middle strategy for an ssl connection.
     * 
     * @param useMITMInSSL Whether or not to use MITM in SSL.
     */
    public static void setUseMITMInSSL(final boolean useMITMInSSL) {
        LittleProxyConfig.useMITMInSSL = useMITMInSSL;
    }

    /**
     * Sets whether or not to trust all SSL certificates when SSL interception is enabled.
     * Was created just to be used within LittleProxy tests. During normal operation
     * the proxy should not, in general, accept un trusted certificates.
     *
     * @param acceptAllSSLCertificates Whether or not to trust all SSL certificates when SSL interception is enabled
     */
    public static void setAcceptAllSSLCertificates(final boolean acceptAllSSLCertificates) {
        LittleProxyConfig.acceptAllSSLCertificates = acceptAllSSLCertificates;
    }

    /**
     * Whether or not we're configured to use DNSSEC for lookups.
     *
     * @return <code>true</code> if configured to use DNSSEC, otherwise
     * <code>false</code>.
     */
    public static boolean isUseDnsSec() {
        return useDnsSec;
    }

    /**
     * Whether or not to use JMX -- defaults to false.
     *
     * @param useJmx Whether or not to use JMX.
     */
    public static void setUseJmx(boolean useJmx) {
        LittleProxyConfig.useJmx = useJmx;
    }

    /**
     * Returns whether or not JMX is turned on.
     *
     * @return <code>true</code> if JMX is turned on, otherwise 
     * <code>false</code>.
     */
    public static boolean isUseJmx() {
        return useJmx;
    }

    public static boolean isUseSSLMitm() {
        return useMITMInSSL;
    }

    public static boolean isAcceptAllSSLCertificates() {
        return acceptAllSSLCertificates;
    }

    public static void setProxyCacheManagerClass(String clazz) {
        proxyCacheManagerClass = clazz;
    }

    public static String getProxyCacheManagerClass() {
        return proxyCacheManagerClass;
    }

    /**
     * Whether or not the proxy adds the 'via' header -- defaults to false.
     *
     * @param transparent if true does not add the 'via' header.
     */
    public static void setTransparent(boolean transparent) {
        LittleProxyConfig.transparent = transparent;
    }

    /**
     * Returns whether or not the proxy adds the 'via' header.
     *
     * @return <code>true</code> if the proxy does not add the 'via' header, otherwise
     * <code>false</code>.
     */
    public static boolean isTransparent() {
        return transparent;
    }
}
