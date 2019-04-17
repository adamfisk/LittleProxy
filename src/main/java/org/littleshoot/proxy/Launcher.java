package org.littleshoot.proxy;

import org.apache.commons.cli.*;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.xml.DOMConfigurator;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;
import org.littleshoot.proxy.impl.ProxyUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * Launches a new HTTP proxy.
 */
public class Launcher {

    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    private static final String OPTION_DNSSEC = "dnssec";

    private static final String OPTION_PORT = "port";

    private static final String OPTION_HELP = "help";

    private static final String OPTION_MITM = "mitm";

    private static final String OPTION_NIC = "nic";

    /**
     * Starts the proxy from the command line.
     * 
     * @param args
     *            Any command line arguments.
     */
    public static void main(final String... args) {
        pollLog4JConfigurationFileIfAvailable();
        LOG.info("Running LittleProxy with args: {}", Arrays.asList(args));
        final Options options = new Options();
        options.addOption(null, OPTION_DNSSEC, true,
                "Request and verify DNSSEC signatures.");
        options.addOption(null, OPTION_PORT, true, "Run on the specified port.");
        options.addOption(null, OPTION_NIC, true, "Run on a specified Nic");
        options.addOption(null, OPTION_HELP, false,
                "Display command line help.");
        options.addOption(null, OPTION_MITM, false, "Run as man in the middle.");
        
        final CommandLineParser parser = new DefaultParser();
        final CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
            if (cmd.getArgs().length > 0) {
                throw new UnrecognizedOptionException(
                        "Extra arguments were provided in "
                                + Arrays.asList(args));
            }
        } catch (final ParseException e) {
            printHelp(options,
                    "Could not parse command line: " + Arrays.asList(args));
            return;
        }
        if (cmd.hasOption(OPTION_HELP)) {
            printHelp(options, null);
            return;
        }
        final int defaultPort = 8080;
        int port;
        if (cmd.hasOption(OPTION_PORT)) {
            final String val = cmd.getOptionValue(OPTION_PORT);
            try {
                port = Integer.parseInt(val);
            } catch (final NumberFormatException e) {
                printHelp(options, "Unexpected port " + val);
                return;
            }
        } else {
            port = defaultPort;
        }


        System.out.println("About to start server on port: " + port);
        HttpProxyServerBootstrap bootstrap = DefaultHttpProxyServer
                .bootstrapFromFile("./littleproxy.properties")
                .withPort(port)
                .withAllowLocalOnly(false);

        if (cmd.hasOption(OPTION_NIC)) {
            final String val = cmd.getOptionValue(OPTION_NIC);
            bootstrap.withNetworkInterface(new InetSocketAddress(val, 0));
        }

        if (cmd.hasOption(OPTION_MITM)) {
            LOG.info("Running as Man in the Middle");
            bootstrap.withManInTheMiddle(new SelfSignedMitmManager());
        }
        
        if (cmd.hasOption(OPTION_DNSSEC)) {
            final String val = cmd.getOptionValue(OPTION_DNSSEC);
            if (ProxyUtils.isTrue(val)) {
                LOG.info("Using DNSSEC");
                bootstrap.withUseDnsSec(true);
            } else if (ProxyUtils.isFalse(val)) {
                LOG.info("Not using DNSSEC");
                bootstrap.withUseDnsSec(false);
            } else {
                printHelp(options, "Unexpected value for " + OPTION_DNSSEC
                        + "=:" + val);
                return;
            }
        }

        System.out.println("About to start...");
        bootstrap.start();
    }

    private static void printHelp(final Options options,
            final String errorMessage) {
        if (!StringUtils.isBlank(errorMessage)) {
            LOG.error(errorMessage);
            System.err.println(errorMessage);
        }

        final HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("littleproxy", options);
    }

    private static void pollLog4JConfigurationFileIfAvailable() {
        File log4jConfigurationFile = new File("src/test/resources/log4j.xml");
        if (log4jConfigurationFile.exists()) {
            DOMConfigurator.configureAndWatch(
                    log4jConfigurationFile.getAbsolutePath(), 15);
        }
    }
}
