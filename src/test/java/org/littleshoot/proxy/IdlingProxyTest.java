package org.littleshoot.proxy;

import static org.junit.Assert.*;

import org.junit.Test;

/**
 * Tests just a single basic proxy.
 */
public class IdlingProxyTest extends AbstractProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(proxyServerPort)
                .withIdleConnectionTimeout(1)
                .start();
    }

    @Test
    public void testTimeout() throws Exception {
        ResponseInfo response = httpGetWithApacheClient(webHost, "/hang", true,
                false);
        assertTrue("Received: " + response, response.getStatusCode() == 504);
    }

}
