package org.littleshoot.proxy;

import org.apache.http.HttpHost;
import org.junit.Test;

/**
 * Base for tests that test the proxy. This base class encapsulates all of the
 * tests and test conditions. Sub-classes should provide different
 * {@link #setUp()} and {@link #tearDown()} methods for testing different
 * configurations of the proxy (e.g. single versus chained, tunneling, etc.).
 */
public abstract class BaseProxyTest extends AbstractProxyTest {
    @Test
    public void testSimpleGetRequest() throws Exception {
        lastResponse =
                compareProxiedAndUnproxiedGET(webHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testSimpleGetRequestOverHTTPS() throws Exception {
        lastResponse =
                compareProxiedAndUnproxiedGET(httpsWebHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testSimplePostRequest() throws Exception {
        lastResponse =
                compareProxiedAndUnproxiedPOST(webHost, DEFAULT_RESOURCE);
    }

    @Test
    public void testSimplePostRequestOverHTTPS() throws Exception {
        lastResponse =
                compareProxiedAndUnproxiedPOST(httpsWebHost, DEFAULT_RESOURCE);
    }

    /**
     * This test tests a HEAD followed by a GET for the same resource, making
     * sure that the requests complete and that the Content-Length matches.
     */
    @Test
    public void testHeadRequestFollowedByGet() throws Exception {
        httpGetWithApacheClient(webHost, DEFAULT_RESOURCE, true, true);
    }

    @Test
    public void testProxyWithBadAddress()
            throws Exception {
        // This test used to try connecting to "test.localhost" and that worked for for local builds, but resulted in
        // the wrong error (405 instead of 502) on the build server due to nginx.  So, switched it to localhost:17,
        // which should work as long as there's not a web server running on the QOTD port.
        ResponseInfo response =
                httpPostWithApacheClient(new HttpHost("localhost", 17),
                        DEFAULT_RESOURCE, true);
        assertReceivedBadGateway(response);
    }

}
