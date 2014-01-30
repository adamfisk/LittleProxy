package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Queue;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.ShutdownHookProcessDestroyer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * <p>This simple program runs two local LittleProxies that talk to each other via
 * two local fteproxy instances (client and server).  After running this program,
 * you can do this:</p>
 * 
 * <pre>
 * curl -x 127.0.0.1:8080 http://www.google.com
 * </pre>
 */
public class FTEMain {
    private static final String FTEPROXY_LOCATION = "/Applications/TorBrowser_en-US.app/Contents/MacOS/fteproxy";
    private static InetAddress LOCALHOST;

    static {
        try {
            LOCALHOST = InetAddress.getByName("127.0.0.1");
        } catch (Exception e) {
            LOCALHOST = null;
            System.err.println("Unable to parse LOCALHOSt");
        }
    }

    private static final int LITTLEPROXY_DOWNSTREAM_PORT = 8080;
    private static final int FTEPROXY_CLIENT_PORT = 8081;
    private static final int FTEPROXY_SERVER_PORT = 8082;
    private static final int LITTLEPROXY_UPSTREAM_PORT = 8083;

    public static void main(String[] args) throws Exception {
        new FTEMain().run();
    }

    public void run() throws Exception {
        // Downstream LittleProxy connects to fteproxy client
        DefaultHttpProxyServer
                .bootstrap()
                .withName("Downstream")
                .withAddress(
                        new InetSocketAddress(LOCALHOST,
                                LITTLEPROXY_DOWNSTREAM_PORT))
                .withConnectTimeout(5000)
                .withChainProxyManager(new ChainedProxyManager() {
                    @Override
                    public void lookupChainedProxies(HttpRequest httpRequest,
                            Queue<ChainedProxy> chainedProxies) {
                        chainedProxies.add(new ChainedProxyAdapter() {
                            @Override
                            public InetSocketAddress getChainedProxyAddress() {
                                return new InetSocketAddress(LOCALHOST,
                                        FTEPROXY_CLIENT_PORT);
                            }
                        });
                    }
                })
                .start();

        // fteproxy client
        fteProxy(
                "--mode", "client",
                "--client_port", FTEPROXY_CLIENT_PORT,
                "--server_port", FTEPROXY_SERVER_PORT);

        // fteproxy server
        fteProxy(
                "--mode", "server",
                "--server_port", FTEPROXY_SERVER_PORT,
                "--proxy_port", LITTLEPROXY_UPSTREAM_PORT);

        // Upstream LittleProxy
        DefaultHttpProxyServer
                .bootstrap()
                .withName("Upstream")
                .withAddress(
                        new InetSocketAddress(LOCALHOST,
                                LITTLEPROXY_UPSTREAM_PORT))
                .start();

    }

    private void fteProxy(Object... args) throws Exception {
        Executor cmdExec = new DefaultExecutor();
        cmdExec.setStreamHandler(new PumpStreamHandler(System.out, System.err,
                System.in));
        cmdExec.setProcessDestroyer(new ShutdownHookProcessDestroyer());
        cmdExec.setWatchdog(new ExecuteWatchdog(
                ExecuteWatchdog.INFINITE_TIMEOUT));
        CommandLine cmd = new CommandLine(FTEPROXY_LOCATION);
        for (Object arg : args) {
            cmd.addArgument(String.format("\"%1$s\"", arg));
        }
        DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
        cmdExec.execute(cmd, resultHandler);
    }
}
