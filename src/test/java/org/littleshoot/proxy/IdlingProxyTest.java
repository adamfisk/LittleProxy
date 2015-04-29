package org.littleshoot.proxy;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests just a single basic proxy.
 */
public class IdlingProxyTest extends AbstractProxyTest {
    @Override
    protected void setUp() {
        this.proxyServer = bootstrapProxy()
                .withPort(0)
                .withIdleConnectionTimeout(1)
                .start();
    }

    @Test
    public void testTimeout() throws Exception {
        ResponseInfo response = httpGetWithApacheClient(webHost, "/hang", true,
                false);
        assertEquals("Received: " + response, 504, response.getStatusCode());
    }

}
