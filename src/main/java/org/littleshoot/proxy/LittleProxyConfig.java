package org.littleshoot.proxy;

/**
 * Simple class for storing LittleProxy configuration. We cheat here and make
 * this all static to avoid the overhead of integrating dependency 
 * injection that could collide with versions of libraries programs including
 * LittleProxy are using.
 */
public class LittleProxyConfig {

    private static boolean useDnsSec;
    
    private LittleProxyConfig(){}

    public static void setUseDnsSec(final boolean useDnsSec) {
        LittleProxyConfig.useDnsSec = useDnsSec;
    }

    public static boolean isUseDnsSec() {
        return useDnsSec;
    }
}
