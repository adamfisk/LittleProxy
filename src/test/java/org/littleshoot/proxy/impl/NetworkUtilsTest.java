package org.littleshoot.proxy.impl;

import static org.junit.Assert.*;

import java.net.InetAddress;

import org.junit.Test;

public class NetworkUtilsTest {
    @Test
    public void testGetLocalhost() throws Exception {
        InetAddress localhost = NetworkUtils.getLocalHost();
        assertFalse(localhost.isLoopbackAddress());
        assertFalse(localhost.isAnyLocalAddress());
    }
}
