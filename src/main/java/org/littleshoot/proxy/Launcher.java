package org.littleshoot.proxy;

import java.util.Arrays;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.cli.UnrecognizedOptionException;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Launches a new HTTP proxy.
 */
public class Launcher {
    
    private static final Logger LOG = LoggerFactory.getLogger(Launcher.class);

    private static final String OPTION_DNSSEC = "dnssec";
    
    private static final String OPTION_PORT = "port";

    private static final String OPTION_HELP = "help";
    
    /**
     * Starts the proxy from the command line.
     * 
     * @param args Any command line arguments.
     */
    public static void main(final String... args) {
        LOG.info("Running LittleProxy with args: {}", Arrays.asList(args));
        final Options options = new Options();
        options.addOption(null, OPTION_DNSSEC, true, 
            "Request and verify DNSSEC signatures.");
        options.addOption(null, OPTION_PORT, true, 
            "Run on the specified port.");
        options.addOption(null, OPTION_HELP, false,
            "Display command line help.");
        final CommandLineParser parser = new PosixParser();
        final CommandLine cmd;
        try {
            cmd = parser.parse(options, args);
            if (cmd.getArgs().length > 0) {
                throw new UnrecognizedOptionException(
                    "Extra arguments were provided in "+Arrays.asList(args));
            }
        }
        catch (final ParseException e) {
            printHelp(options, "Could not parse command line: "+Arrays.asList(args));
            return;
        }
        if (cmd.hasOption(OPTION_HELP)) {
            printHelp(options, null);
            return;
        }
        if (cmd.hasOption(OPTION_DNSSEC)) {
            final String val = cmd.getOptionValue(OPTION_DNSSEC);
            if (ProxyUtils.isTrue(val)) {
                LOG.info("Using DNSSEC");
                LittleProxyConfig.setUseDnsSec(true);
            } else if (ProxyUtils.isFalse(val)) {
                LOG.info("Not using DNSSEC");
                LittleProxyConfig.setUseDnsSec(false);
            } else {
                printHelp(options, "Unexpected value for "+OPTION_DNSSEC+"=:"+val);
                return;
            }
        }
        final int defaultPort = 8080;
        int port;
        if (cmd.hasOption(OPTION_PORT)) {
            final String val = cmd.getOptionValue(OPTION_PORT);
            try {
                port = Integer.parseInt(val);
            } catch (final NumberFormatException e) {
                printHelp(options, "Unexpected port "+val);
                return;
            }
        } else {
            port = defaultPort;
        }
        
        System.out.println("About to start server on port: "+port);
        final HttpProxyServer server = new DefaultHttpProxyServer(port);
        System.out.println("About to start...");
        server.start();
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
}
