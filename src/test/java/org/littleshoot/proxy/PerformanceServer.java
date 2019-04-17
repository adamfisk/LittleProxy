package org.littleshoot.proxy;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.server.handler.ResourceHandler;

/**
 * This launches a Jetty-based HTTP server that serves static files from the
 * perfsite folder.
 */
public class PerformanceServer {
    public void run(int port) throws Exception {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(port);
        server.addConnector(connector);

        ResourceHandler resource_handler = new ResourceHandler();
        resource_handler.setDirectoriesListed(true);
        resource_handler.setWelcomeFiles(new String[] { "index.html" });

        resource_handler.setResourceBase("./performance/site/");

        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { resource_handler,
                new DefaultHandler() });
        server.setHandler(handlers);

        server.start();
        System.out.println("Started performance file server at port: " + port);
        server.join();
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9000;
        new PerformanceServer().run(port);
    }
}
