package org.littleshoot.proxy.impl;

import io.netty.handler.codec.http.*;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.*;

/**
 * Test for proxy utilities.
 */
public class ProxyUtilsTest {

    @Test
    public void testParseHostAndPort() {
        assertEquals("www.test.com:80", ProxyUtils.parseHostAndPort("http://www.test.com:80/test"));
        assertEquals("www.test.com:80", ProxyUtils.parseHostAndPort("https://www.test.com:80/test"));
        assertEquals("www.test.com:443", ProxyUtils.parseHostAndPort("https://www.test.com:443/test"));
        assertEquals("www.test.com:80", ProxyUtils.parseHostAndPort("www.test.com:80/test"));
        assertEquals("www.test.com", ProxyUtils.parseHostAndPort("http://www.test.com"));
        assertEquals("www.test.com", ProxyUtils.parseHostAndPort("www.test.com"));
        assertEquals("httpbin.org:443", ProxyUtils.parseHostAndPort("httpbin.org:443/get"));
    }

    @Test
    public void testAddNewViaHeader() {
        String hostname = "hostname";

        HttpMessage httpMessage = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/endpoint");
        ProxyUtils.addVia(httpMessage, hostname);

        List<String> viaHeaders = httpMessage.headers().getAll(HttpHeaderNames.VIA);
        assertThat(viaHeaders, hasSize(1));

        String expectedViaHeader = "1.1 " + hostname;
        assertEquals(expectedViaHeader, viaHeaders.get(0));
    }

    @Test
    public void testCommaSeparatedHeaderValues() {
        DefaultHttpMessage message;
        List<String> commaSeparatedHeaders;

        // test the empty headers case
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, empty());

        // two headers present, but no values
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "");
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, empty());

        // a single header value
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("chunked"));

        // a single header value with extra spaces
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, " chunked  , ");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("chunked"));

        // two comma-separated values in one header line
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "compress, gzip");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("compress", "gzip"));

        // two comma-separated values in one header line with a spurious ',' and space. see RFC 7230 section 7
        // for information on empty list items (not all of which are valid header-values).
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "compress, gzip, ,");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("compress", "gzip"));

        // two values in two separate header lines
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip");
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("gzip", "chunked"));

        // multiple comma-separated values in two separate header lines
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip, compress");
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "deflate, gzip");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("gzip", "compress", "deflate", "gzip"));

        // multiple comma-separated values in multiple header lines with spurious spaces, commas,
        // and tabs (horizontal tabs are defined as optional whitespace in RFC 7230 section 3.2.3)
        message = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, " gzip,compress,");
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "\tdeflate\t,  gzip, ");
        message.headers().add(HttpHeaderNames.TRANSFER_ENCODING, ",gzip,,deflate,\t, ,");
        commaSeparatedHeaders = ProxyUtils.getAllCommaSeparatedHeaderValues(HttpHeaderNames.TRANSFER_ENCODING, message);
        assertThat(commaSeparatedHeaders, contains("gzip", "compress", "deflate", "gzip", "gzip", "deflate"));
    }

    @Test
    public void testIsResponseSelfTerminating() {
        HttpResponse httpResponse;
        boolean isResponseSelfTerminating;

        // test cases from the scenarios listed in RFC 2616, section 4.4
        // #1: 1.Any response message which "MUST NOT" include a message-body (such as the 1xx, 204, and 304 responses and any response to a HEAD request) is always terminated by the first empty line after the header fields, regardless of the entity-header fields present in the message.
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.CONTINUE);
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.SWITCHING_PROTOCOLS);
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT);
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.RESET_CONTENT);
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_MODIFIED);
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        // #2: 2.If a Transfer-Encoding header field (section 14.41) is present and has any value other than "identity", then the transfer-length is defined by use of the "chunked" transfer-coding (section 3.6), unless the message is terminated by closing the connection.
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        // chunked encoding is not last, so not self terminating
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "chunked, gzip");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertFalse(isResponseSelfTerminating);

        // four encodings on two lines, chunked is not last, so not self terminating
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "deflate, gzip");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertFalse(isResponseSelfTerminating);

        // three encodings on two lines, chunked is last, so self terminating
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip");
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "deflate,chunked");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        // #3: 3.If a Content-Length header field (section 14.13) is present, its decimal value in OCTETs represents both the entity-length and the transfer-length.
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.CONTENT_LENGTH, "15");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        // continuing #3: If a message is received with both a Transfer-Encoding header field and a Content-Length header field, the latter MUST be ignored.

        // chunked is last Transfer-Encoding, so message is self-terminating
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip, chunked");
        httpResponse.headers().add(HttpHeaderNames.CONTENT_LENGTH, "15");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertTrue(isResponseSelfTerminating);

        // chunked is not last Transfer-Encoding, so message is not self-terminating, since Content-Length is ignored
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        httpResponse.headers().add(HttpHeaderNames.TRANSFER_ENCODING, "gzip");
        httpResponse.headers().add(HttpHeaderNames.CONTENT_LENGTH, "15");
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertFalse(isResponseSelfTerminating);

        // without any of the above conditions, the message should not be self-terminating
        // (multipart/byteranges is ignored, see note in method javadoc)
        httpResponse = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        isResponseSelfTerminating = ProxyUtils.isResponseSelfTerminating(httpResponse);
        assertFalse(isResponseSelfTerminating);

    }

    @Test
    public void testAddNewViaHeaderToExistingViaHeader() {
        String hostname = "hostname";

        HttpMessage httpMessage = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/endpoint");
        httpMessage.headers().add(HttpHeaderNames.VIA, "1.1 otherproxy");
        ProxyUtils.addVia(httpMessage, hostname);

        List<String> viaHeaders = httpMessage.headers().getAll(HttpHeaderNames.VIA);
        assertThat(viaHeaders, hasSize(2));

        assertEquals("1.1 otherproxy", viaHeaders.get(0));

        String expectedViaHeader = "1.1 " + hostname;
        assertEquals(expectedViaHeader, viaHeaders.get(1));
    }

    @Test
    public void testSplitCommaSeparatedHeaderValues() {
        assertThat("Incorrect header tokens", ProxyUtils.splitCommaSeparatedHeaderValues("one"), contains("one"));
        assertThat("Incorrect header tokens", ProxyUtils.splitCommaSeparatedHeaderValues("one,two,three"), contains("one", "two", "three"));
        assertThat("Incorrect header tokens", ProxyUtils.splitCommaSeparatedHeaderValues("one, two, three"), contains("one", "two", "three"));
        assertThat("Incorrect header tokens", ProxyUtils.splitCommaSeparatedHeaderValues(" one,two,  three "), contains("one", "two", "three"));
        assertThat("Incorrect header tokens", ProxyUtils.splitCommaSeparatedHeaderValues("\t\tone ,\t two,  three\t"), contains("one", "two", "three"));

        assertThat("Expected no header tokens", ProxyUtils.splitCommaSeparatedHeaderValues(""), empty());
        assertThat("Expected no header tokens", ProxyUtils.splitCommaSeparatedHeaderValues(","), empty());
        assertThat("Expected no header tokens", ProxyUtils.splitCommaSeparatedHeaderValues(" "), empty());
        assertThat("Expected no header tokens", ProxyUtils.splitCommaSeparatedHeaderValues("\t"), empty());
        assertThat("Expected no header tokens", ProxyUtils.splitCommaSeparatedHeaderValues("  \t  \t  "), empty());
        assertThat("Expected no header tokens", ProxyUtils.splitCommaSeparatedHeaderValues(" ,  ,\t, "), empty());
    }

    /**
     * Verifies that 'sdch' is removed from the 'Accept-Encoding' header list.
     */
    @Test
    public void testRemoveSdchEncoding() {
        final List<String> emptyList = new ArrayList<>();
        // Various cases where 'sdch' is not present within the accepted
        // encodings list
        assertRemoveSdchEncoding(singletonList(""), emptyList);
        assertRemoveSdchEncoding(singletonList("gzip"), singletonList("gzip"));

        assertRemoveSdchEncoding(Arrays.asList("gzip", "deflate", "br"), Arrays.asList("gzip", "deflate", "br"));
        assertRemoveSdchEncoding(singletonList("gzip, deflate, br"), singletonList("gzip, deflate, br"));

        // Various cases where 'sdch' is present within the accepted encodings
        // list
        assertRemoveSdchEncoding(singletonList("sdch"), emptyList);
        assertRemoveSdchEncoding(singletonList("SDCH"), emptyList);

        assertRemoveSdchEncoding(Arrays.asList("sdch", "gzip"), singletonList("gzip"));
        assertRemoveSdchEncoding(singletonList("sdch, gzip"), singletonList("gzip"));

        assertRemoveSdchEncoding(Arrays.asList("gzip", "sdch", "deflate"), Arrays.asList("gzip", "deflate"));
        assertRemoveSdchEncoding(singletonList("gzip, sdch, deflate"), singletonList("gzip, deflate"));
        assertRemoveSdchEncoding(singletonList("gzip,deflate,sdch"), singletonList("gzip,deflate"));

        assertRemoveSdchEncoding(Arrays.asList("gzip", "deflate, sdch", "br"), Arrays.asList("gzip", "deflate", "br"));
    }

    /**
     * Helper method that asserts that 'sdch' is removed from the
     * 'Accept-Encoding' header.
     *
     * @param inputEncodings The input list that maps to the values of the
     *        'Accept-Encoding' header that should be used as the basis for the
     *        assertion check.
     * @param expectedEncodings The list containing the expected values of the
     *        'Accept-Encoding' header after the 'sdch' encoding is removed.
     */
    private void assertRemoveSdchEncoding(List<String> inputEncodings, List<String> expectedEncodings) {
        HttpHeaders headers = new DefaultHttpHeaders();

        for (String encoding : inputEncodings) {
            headers.add(HttpHeaderNames.ACCEPT_ENCODING, encoding);
        }

        ProxyUtils.removeSdchEncoding(headers);
        assertEquals(expectedEncodings, headers.getAll(HttpHeaderNames.ACCEPT_ENCODING));
    }
}
