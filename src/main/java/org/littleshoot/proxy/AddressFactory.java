package org.littleshoot.proxy;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

import org.littleshoot.dnssec4j.DnsSec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xbill.DNS.DNSSEC.DNSSECException;

/**
 * Class for creating addresses. This class is DNSSEC-aware, so will attempt
 * to use DNSSEC if configured to do so.
 */
public class AddressFactory {
    
    private static final Logger LOG = 
        LoggerFactory.getLogger(AddressFactory.class);

    /**
     * Creates a new InetSocketAddress, verifying the host with DNSSEC if 
     * configured to do so.
     * 
     * @param host The host.
     * @param port The port.
     * @return The endpoint.
     */
    public static SocketAddress newInetSocketAddress(final String host, 
        final int port) {
        if (LittleProxyConfig.isUseDnsSec()) {
            try {
                final InetAddress verifiedHost = DnsSec.getByName(host);
                return new InetSocketAddress(verifiedHost, port);
            } catch (final IOException e) {
                LOG.info("Could not resolve address for: "+host, e);
            } catch (final DNSSECException e) {
                LOG.warn("DNSSEC error. Bad signature?", e);
                throw new Error("DNSSEC error. Bad signature?", e);
            }
        }
        return new InetSocketAddress(host, port);
    }

}
