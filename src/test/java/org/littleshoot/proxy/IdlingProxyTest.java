package org.littleshoot.proxy;

import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.assertEquals;

/**
 * Tests just a single basic proxy.
 */
@Category(SlowTest.class)
public class IdlingProxyTest extends AbstractProxyTest {
    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy()
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
