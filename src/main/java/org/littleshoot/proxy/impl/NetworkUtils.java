package org.littleshoot.proxy.impl;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Enumeration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Network utilities methods.
 */
public class NetworkUtils {

    private static final Logger LOG =
            LoggerFactory.getLogger(NetworkUtils.class);

    /**
     * Many Linux systems typically return 127.0.0.1 as the localhost address
     * instead of the address assigned on the local network. It has to do with
     * how localhost is defined in /etc/hosts. This method creates a quick UDP
     * socket and gets the local address for the socket on Linux systems to get
     * around the problem.
     * 
     * @return The local network address in a cross-platform manner.
     * @throws UnknownHostException
     *             If the host is considered unknown for any reason.
     */
    public static InetAddress getLocalHost() throws UnknownHostException {
        try {
            final InetAddress is = InetAddress.getLocalHost();
            if (!is.isLoopbackAddress()) {
                return is;
            }
        } catch (final UnknownHostException e) {
            // This can happen in odd cases like when using network cards.
            // Continue to try via UDP.
        }

        InetAddress address = getLocalHostViaUdp();
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()) {
            // UDP didn't work, resort to looking at interfaces
            InetAddress alternateAddress = firstLocalNonLoopbackIpv4Address();
            if (alternateAddress != null) {
                address = alternateAddress;
            }
        }
        return address;
    }

    private static InetAddress getLocalHostViaUdp() throws UnknownHostException {
        final InetSocketAddress sa = new InetSocketAddress("www.google.com", 80);

        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            sock.connect(sa);
            final InetAddress address = sock.getLocalAddress();
            return address;
        } catch (final SocketException e) {
            LOG.info("Exception getting address", e);
            return InetAddress.getLocalHost();
        } finally {
            if (sock != null) {
                sock.close();
            }
        }
    }

    /**
     * Go through our network interfaces and find the first bound address for an
     * up interface that's in the IPv4 address space and is not the loopback
     * address.
     * 
     * @return
     */
    public static InetAddress firstLocalNonLoopbackIpv4Address() {
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface
                    .getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces
                        .nextElement();
                if (networkInterface.isUp()) {
                    for (InterfaceAddress ifAddress : networkInterface
                            .getInterfaceAddresses()) {
                        if (ifAddress.getNetworkPrefixLength() > 0
                                && ifAddress.getNetworkPrefixLength() <= 32
                                && !ifAddress.getAddress().isLoopbackAddress()) {
                            return ifAddress.getAddress();
                        }
                    }
                }
            }
            return null;
        } catch (SocketException se) {
            return null;
        }
    }

}
