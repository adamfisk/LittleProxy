package org.littleshoot.proxy.impl;

import java.net.*;
import java.util.Enumeration;

/**
 * @deprecated This class is no longer used by LittleProxy and may be removed in a future release.
 */
@Deprecated
public class NetworkUtils {
    /**
     * @deprecated This method is no longer used by LittleProxy and may be removed in a future release.
     */
    @Deprecated
    public static InetAddress getLocalHost() throws UnknownHostException {
        return InetAddress.getLocalHost();
    }

    /**
     * @deprecated This method is no longer used by LittleProxy and may be removed in a future release.
     */
    @Deprecated
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
