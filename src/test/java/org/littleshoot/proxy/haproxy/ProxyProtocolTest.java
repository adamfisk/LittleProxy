package org.littleshoot.proxy.haproxy;

import io.netty.handler.codec.haproxy.HAProxyMessage;
import org.junit.Assert;
import org.junit.Test;

public class ProxyProtocolTest extends BaseProxyProtocolTest {


    private static final String LOCALHOST = "127.0.0.1";
    private static final boolean ACCEPT_PROXY = true;
    private static final boolean SEND_PROXY = true;
    private static final boolean DO_NOT_ACCEPT_PROXY = false;
    private static final boolean DO_NOT_SEND_PROXY = false;

    @Test
    public void canRelayProxyProtocolHeader() throws Exception {
        setup(ACCEPT_PROXY, SEND_PROXY);
        HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
        Assert.assertNotNull(haProxyMessage);
        Assert.assertEquals(SOURCE_ADDRESS, haProxyMessage.sourceAddress());
        Assert.assertEquals(DESTINATION_ADDRESS, haProxyMessage.destinationAddress());
        Assert.assertEquals(SOURCE_PORT, String.valueOf(haProxyMessage.sourcePort()));
        Assert.assertEquals(DESTINATION_PORT, String.valueOf(haProxyMessage.destinationPort()));
    }

    @Test
    public void canSendProxyProtocolHeader() throws Exception {
        setup(DO_NOT_ACCEPT_PROXY, SEND_PROXY);
        HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
        Assert.assertNotNull(haProxyMessage);
        Assert.assertEquals(LOCALHOST, haProxyMessage.sourceAddress());
        Assert.assertEquals(LOCALHOST, haProxyMessage.destinationAddress());
        Assert.assertEquals(String.valueOf(serverPort), String.valueOf(haProxyMessage.destinationPort()));
    }

    @Test
    public void canAcceptProxyProtocolHeader() throws Exception {
        setup(ACCEPT_PROXY, DO_NOT_SEND_PROXY);
        HAProxyMessage haProxyMessage = getRelayedHaProxyMessage();
        Assert.assertNull(haProxyMessage);
    }


}
