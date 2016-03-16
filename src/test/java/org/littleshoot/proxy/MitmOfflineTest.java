package org.littleshoot.proxy;

import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
import java.net.UnknownHostException;

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

import org.apache.http.HttpHost;
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

    private HttpHost httpHost;

    private HttpHost secureHost;

    @Override
    protected void setUp() {
        httpHost = new HttpHost("unknown", 80, "http");
        secureHost = new HttpHost("unknown", 443, "https");
        proxyServer = bootstrapProxy().withPort(0)
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
                            return new HttpFiltersAdapter(originalRequest, ctx);
                        }

                        return new HttpFiltersAdapter(originalRequest, ctx) {

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

                }).withServerResolver(new HostResolver() {
                    @Override
                    public InetSocketAddress resolve(String host, int port)
                            throws UnknownHostException {

                        // This unresolved address marks the Offline mode,
                        // checked in ProxyToServerConnection, to suppress the
                        // server handshake.
                        //
                        return new InetSocketAddress(host, port);
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
        ResponseInfo actual = httpGetWithApacheClient(httpHost,
                DEFAULT_RESOURCE, true, false);
        assertEquals(EXPEXTED, actual);
    }

    @Test
    public void testSimpleGetRequestOverHTTPSOffline() throws Exception {
        ResponseInfo actual = httpGetWithApacheClient(secureHost,
                DEFAULT_RESOURCE, true, false);
        assertEquals(EXPEXTED, actual);
    }

    @Test
    public void testSimplePostRequestOffline() throws Exception {
        ResponseInfo actual = httpPostWithApacheClient(httpHost,
                DEFAULT_RESOURCE, true);
        assertEquals(EXPEXTED, actual);
    }

    @Test
    public void testSimplePostRequestOverHTTPSOffline() throws Exception {
        ResponseInfo actual = httpPostWithApacheClient(secureHost,
                DEFAULT_RESOURCE, true);
        assertEquals(EXPEXTED, actual);
    }

}
