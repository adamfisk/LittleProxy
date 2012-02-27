package org.littleshoot.proxy;

import static org.junit.Assert.*;

import java.util.List;
import java.util.Set;
import java.util.Map.Entry;

import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.junit.Test;

/**
 * Test for HTTP request URI rules.
 */
public class HttpRequestUriRuleTest {

    @Test public void testMatching() throws Exception {
        final HttpRequestPathMatcher matcher = 
            new HttpRequestPathMatcher("/search");
        
        assertTrue("Rule should have matches request", 
            matcher.filterResponses(new HttpRequest() {

            public HttpMethod getMethod() {
                // TODO Auto-generated method stub
                return null;
            }

            public String getUri() {
                return "/search?hl=en&source=hp&q=bop&aq=f&aqi=g10&aql=&oq=";
            }

            public void setMethod(HttpMethod method) {
                // TODO Auto-generated method stub
                
            }

            public void setUri(String uri) {
                // TODO Auto-generated method stub
                
            }

            public void addHeader(String name, Object value) {
                // TODO Auto-generated method stub
                
            }

            public void clearHeaders() {
                // TODO Auto-generated method stub
                
            }

            public boolean containsHeader(String name) {
                // TODO Auto-generated method stub
                return false;
            }

            public ChannelBuffer getContent() {
                // TODO Auto-generated method stub
                return null;
            }

            public long getContentLength() {
                // TODO Auto-generated method stub
                return 0;
            }

            public long getContentLength(long defaultValue) {
                // TODO Auto-generated method stub
                return 0;
            }

            public String getHeader(String name) {
                // TODO Auto-generated method stub
                return null;
            }

            public Set<String> getHeaderNames() {
                // TODO Auto-generated method stub
                return null;
            }

            public List<Entry<String, String>> getHeaders() {
                // TODO Auto-generated method stub
                return null;
            }

            public List<String> getHeaders(String name) {
                // TODO Auto-generated method stub
                return null;
            }

            public HttpVersion getProtocolVersion() {
                // TODO Auto-generated method stub
                return null;
            }

            public boolean isChunked() {
                // TODO Auto-generated method stub
                return false;
            }

            public boolean isKeepAlive() {
                // TODO Auto-generated method stub
                return false;
            }

            public void removeHeader(String name) {
                // TODO Auto-generated method stub
                
            }

            public void setChunked(boolean chunked) {
                // TODO Auto-generated method stub
                
            }

            public void setContent(ChannelBuffer content) {
                // TODO Auto-generated method stub
                
            }

            public void setHeader(String name, Object value) {
                // TODO Auto-generated method stub
                
            }

            public void setHeader(String name, Iterable<?> values) {
                // TODO Auto-generated method stub
                
            }

            public void setProtocolVersion(HttpVersion version) {
                // TODO Auto-generated method stub
                
            }
            
        }));
    }
}
