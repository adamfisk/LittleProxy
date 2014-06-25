package org.littleshoot.proxy;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

import org.littleshoot.dnssec4j.VerifiedAddressFactory;

public class DnsSecServerResolver implements HostResolver {
    @Override
    public InetSocketAddress resolve(String host, int port)
            throws UnknownHostException {
        return VerifiedAddressFactory.newInetSocketAddress(host, port, true);
    }
    
    @Override
	public InetSocketAddress resolveLocalAddr() throws SocketException {
		throw new RuntimeException("Not implemented method");
	}
}
