package org.littleshoot.proxy;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.exec.ShutdownHookProcessDestroyer;

/**
 * <p>
 * This simple program runs two local LittleProxies that talk to each other via
 * two local fteproxy instances (client and server). After running this program,
 * you can do this:
 * </p>
 * 
 * <pre>
 * curl -x 127.0.0.1:8080 http://www.google.com
 * </pre>
 */
public class FTEMain extends ChainedMain {
    private static final String FTEPROXY_LOCATION = "/Applications/TorBrowser_en-US.app/Contents/MacOS/fteproxy";
    private static final int FTEPROXY_CLIENT_PORT = 8081;
    private static final int FTEPROXY_SERVER_PORT = 8082;

    public static void main(String[] args) throws Exception {
        new FTEMain().run();
    }

    public void run() throws Exception {
        // Start LittleProxy servers
        super.run();

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
