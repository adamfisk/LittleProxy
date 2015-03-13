package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import org.junit.Assert;
import org.junit.Test;
import org.littleshoot.proxy.extras.SelfSignedMitmManager;
import org.littleshoot.proxy.impl.ProxyUtils;

/**
 * Tests a proxy running as a man in the middle without server connection. The
 * purpose is to store traffic while Online and spool it in an Offline mode.
 */
public class MitmOfflineTest extends AbstractProxyTest {

    private static final String OFFLINE_RESPONSE = "Offline response";

    private static final ResponseInfo EXPEXTED = new ResponseInfo(200,
            OFFLINE_RESPONSE);

    @Override
    protected void setUp() {
        proxyServer = bootstrapProxy().withPort(proxyServerPort)
                .withManInTheMiddle(new SelfSignedMitmManager())
                .withFiltersSource(new HttpFiltersSourceAdapter() {
                    @Override
                    public HttpFilters filterRequest(
                            HttpRequest originalRequest,
                            ChannelHandlerContext ctx) {

                        // The connect request must bypass the filter! Otherwise
                        // the handshake will fail.
                        //
                        if (ProxyUtils.isCONNECT(originalRequest)) {
                            return new HttpFiltersAdapter(originalRequest);
                        }

                        return new HttpFiltersAdapter(originalRequest) {

                            // This filter delivers special responses while
                            // connection is limited
                            //
                            @Override
                            public HttpResponse clientToProxyRequest(
                                    HttpObject httpObject) {
                                return createOfflineResponse();
                            }

                        };
                    }

                }).start();
    }

    private HttpResponse createOfflineResponse() {
        ByteBuf buffer = Unpooled.wrappedBuffer(OFFLINE_RESPONSE.getBytes());
        HttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK, buffer);
        HttpHeaders.setContentLength(response, buffer.readableBytes());
        HttpHeaders.setHeader(response, HttpHeaders.Names.CONTENT_TYPE,
                "text/html");
        return response;
    }

    @Test
    public void testSimpleGetRequestOffline() throws Exception {
        ResponseInfo actual = httpGetWithApacheClient(webHost,
                DEFAULT_RESOURCE, true, false);
        assertEquals(EXPEXTED, actual);
    }

    @Test
    public void testSimpleGetRequestOverHTTPSOffline() throws Exception {
        ResponseInfo actual = httpGetWithApacheClient(httpsWebHost,
                DEFAULT_RESOURCE, true, false);
        Assert.assertEquals(EXPEXTED, actual);
    }

    @Test
    public void testSimplePostRequestOffline() throws Exception {
        ResponseInfo actual = httpPostWithApacheClient(webHost,
                DEFAULT_RESOURCE, true);
        Assert.assertEquals(EXPEXTED, actual);
    }

    @Test
    public void testSimplePostRequestOverHTTPSOffline() throws Exception {
        ResponseInfo actual = httpPostWithApacheClient(httpsWebHost,
                DEFAULT_RESOURCE, true);
        Assert.assertEquals(EXPEXTED, actual);
    }

}
