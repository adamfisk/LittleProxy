package org.littleshoot.proxy;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import org.littleshoot.proxy.common.Constants;

/**
 * Default implementation of {@link HostResolver} that just uses
 * {@link InetAddress#getByName(String)}.
 */
public class DefaultHostResolver implements HostResolver {
    @Override
    public InetSocketAddress resolve(String host, int port)
            throws UnknownHostException {
        InetAddress addr = InetAddress.getByName(host);
        return new InetSocketAddress(addr, port);
    }
    
    @Override
	public InetSocketAddress resolveLocalAddr()
			throws SocketException {
		InetSocketAddress isaLocal = null;
		
		if ( Constants.NICIP != null ) {
			isaLocal = new InetSocketAddress(Constants.NICIP, 0);
		}
		
		return isaLocal;
	}
}
