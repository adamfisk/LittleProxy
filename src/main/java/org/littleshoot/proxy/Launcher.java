package org.littleshoot.proxy;

import java.util.Collections;

import org.jboss.netty.handler.codec.http.HttpRequest;


/**
 * Launches a new HTTP proxy.
 */
public class Launcher {

    /**
     * Starts the proxy from the command line.
     * 
     * @param args Any command line arguments.
     */
    public static void main(final String... args) {
        ChainProxyManager chainProxyManager = new ChainProxyManager() {
	    public String getChainProxy(HttpRequest httpRequest) {
		String host = httpRequest.getHeader("Host");
		
		if ("www.sme.sk".equals(host)) {
		    return "127.0.0.1:8080";
		}
		
		return null;
	    }
	};
	final HttpProxyServer server = new DefaultHttpProxyServer(8080, Collections.<String, HttpFilter>emptyMap(), chainProxyManager, null, null);

//        final int defaultPort = 8080;
//        int port;
//        if (args.length > 0) {
//            final String arg = args[0];
//            try {
//                port = Integer.parseInt(arg);
//            } catch (final NumberFormatException e) {
//                port = defaultPort;
//            }
//        } else {
//            port = defaultPort;
//        }
//        
//        System.out.println("About to start server on port: "+port);
//        final HttpProxyServer server = new DefaultHttpProxyServer(port);
//        System.out.println("About to start...");
        server.start();
    }
}
