package org.littleshoot.proxy;

import static org.junit.Assert.*;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseEncoder;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

/**
 * Test for the proxy cache manager.
 */
public class DefaultProxyCacheManagerTest {

    @Test public void testCaching() throws Exception {
        final DefaultProxyCacheManager cm = new DefaultProxyCacheManager();
        final HttpRequest httpRequest = 
            new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, 
                "http://www.littleshoot.org");
        final HttpResponse httpResponse = 
            new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.setHeader(HttpHeaders.Names.CACHE_CONTROL, HttpHeaders.Values.PUBLIC);
        final class PubEncoder extends HttpResponseEncoder {
            public Object pubEncode(ChannelHandlerContext ctx, Channel channel, Object msg) throws Exception {
                return encode(ctx, channel, msg);
            }
        };
        final PubEncoder encoder = new PubEncoder();
        Channel channel = new ChannelAdapter();
        final ChannelBuffer encoded = (ChannelBuffer) encoder.pubEncode(null, channel, httpResponse);
        final Future<String> future = cm.cache(httpRequest, httpResponse, httpResponse, encoded);
        
        assertNotNull("No future?", future);
        future.get(2000, TimeUnit.MILLISECONDS);
        assertTrue("No hit in cache!!", cm.returnCacheHit(httpRequest, channel));
    }
}
