package org.littleshoot.proxy;

import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
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
     * how localhost is defined in /etc/hosts. This method creates a quick
     * UDP socket and gets the local address for the socket on Linux systems
     * to get around the problem. 
     * 
     * @return The local network address in a cross-platform manner.
     * @throws UnknownHostException If the host is considered unknown for 
     * any reason.
     */
    public static InetAddress getLocalHost() throws UnknownHostException {
        final InetAddress is = InetAddress.getLocalHost();
        if (!is.isLoopbackAddress()) {
            return is;
        }

        return getLocalHostViaUdp();
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
            LOG.warn("Exception getting address", e);
            return InetAddress.getLocalHost();
        } finally {
            if (sock != null) {
                sock.close();
            }
        }
    }

    /**
     * Returns whether or not the specified address represents an address on 
     * the public Internet.
     * 
     * @param ia The address to check.
     * @return <code>true</code> if the address is an address on the public
     * Internet, otherwise <code>false</code>.
     */
    public static boolean isPublicAddress(final InetAddress ia) {
        // We define public addresses by what they're not.  A public address
        // cannot be any one of the following:
        return 
            !ia.isSiteLocalAddress() &&
            !ia.isLinkLocalAddress() &&
            !ia.isAnyLocalAddress() &&
            !ia.isLoopbackAddress() &&
            !ia.isMulticastAddress();
    }

    /**
     * Returns whether or not this host is on the public Internet.
     * 
     * @return <code>true</code> if this host is on the public Internet,
     * otherwise <code>false</code>.
     */
    public static boolean isPublicAddress() {
        try {
            return isPublicAddress(getLocalHost());
        } catch (final UnknownHostException e) {
            LOG.warn("Could not get address", e);
            return false;
        }
    }
    
    /**
     * Utility method for accessing public interfaces.
     * 
     * @return The {@link Collection} of public interfaces.
     * @throws SocketException If there's a socket error accessing the
     * interfaces.
     */
    public static Collection<InetAddress> getNetworkInterfaces()
            throws SocketException {
        final Collection<InetAddress> addresses = new ArrayList<InetAddress>();
        final Enumeration<NetworkInterface> e = 
            NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements()) {
            final NetworkInterface ni = e.nextElement();
            final Enumeration<InetAddress> niAddresses = ni.getInetAddresses();
            while (niAddresses.hasMoreElements()) {
                addresses.add(niAddresses.nextElement());
            }
        }
        return addresses;
    }
}
