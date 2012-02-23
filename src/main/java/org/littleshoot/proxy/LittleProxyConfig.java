package org.littleshoot.proxy;

/**
 * Simple class for storing configuration. We cheat here and make
 * this all static to avoid the overhead of integrating dependency 
 * injection that could collide with versions of libraries programs including
 * this library are using.
 */
public class LittleProxyConfig {

    private static boolean useDnsSec = false;
    
    private LittleProxyConfig(){}

    /**
     * Sets whether or not to use DNSSEC to request signed records when
     * performing DNS lookups and verifying those records if they exist.
     * 
     * @param useDnsSec Whether or not to use DNSSEC.
     */
    public static void setUseDnsSec(final boolean useDnsSec) {
        LittleProxyConfig.useDnsSec = useDnsSec;
    }

    /**
     * Whether or not we're configured to use DNSSEC for lookups.
     * 
     * @return <code>true</code> if configured to use DNSSEC, otherwise
     * <code>false</code>.
     */
    public static boolean isUseDnsSec() {
        return useDnsSec;
    }
}
