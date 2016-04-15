package org.littleshoot.proxy.impl;

import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.udt.nio.NioUdtProvider;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * Utilities for the proxy.
 */
public class ProxyUtils {
    /**
     * Hop-by-hop headers that should be removed when proxying, as defined by the HTTP 1.1 spec, section 13.5.1
     * (http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.1). Transfer-Encoding is NOT included in this list, since LittleProxy
     * does not typically modify the transfer encoding. See also {@link #shouldRemoveHopByHopHeader(String)}.
     *
     * Header names are stored as lowercase to make case-insensitive comparisons easier.
     */
    private static final Set<String> SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS = ImmutableSet.of(
            HttpHeaders.Names.CONNECTION.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHENTICATE.toLowerCase(Locale.US),
            HttpHeaders.Names.PROXY_AUTHORIZATION.toLowerCase(Locale.US),
            HttpHeaders.Names.TE.toLowerCase(Locale.US),
            HttpHeaders.Names.TRAILER.toLowerCase(Locale.US),
            /*  Note: Not removing Transfer-Encoding since LittleProxy does not normally re-chunk content.
                HttpHeaders.Names.TRANSFER_ENCODING.toLowerCase(Locale.US), */
            HttpHeaders.Names.UPGRADE.toLowerCase(Locale.US),
            "Keep-Alive".toLowerCase(Locale.US)
    );

    private static final Logger LOG = LoggerFactory.getLogger(ProxyUtils.class);

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    /**
     * Splits comma-separated header values (such as Connection) into their individual tokens.
     */
    private static final Splitter COMMA_SEPARATED_HEADER_VALUE_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    /**
     * Date format pattern used to parse HTTP date headers in RFC 1123 format.
     */
    private static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    // Schemes are case-insensitive:
    // http://tools.ietf.org/html/rfc3986#section-3.1
    private static Pattern HTTP_PREFIX = Pattern.compile("^https?://.*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Strips the host from a URI string. This will turn "http://host.com/path"
     * into "/path".
     * 
     * @param uri
     *            The URI to transform.
     * @return A string with the URI stripped.
     */
    public static String stripHost(final String uri) {
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // It's likely a URI path, not the full URI (i.e. the host is
            // already stripped).
            return uri;
        }
        final String noHttpUri = StringUtils.substringAfter(uri, "://");
        final int slashIndex = noHttpUri.indexOf("/");
        if (slashIndex == -1) {
            return "/";
        }
        final String noHostUri = noHttpUri.substring(slashIndex);
        return noHostUri;
    }

    /**
     * Formats the given date according to the RFC 1123 pattern.
     * 
     * @param date
     *            The date to format.
     * @return An RFC 1123 formatted date string.
     * 
     * @see #PATTERN_RFC1123
     */
    public static String formatDate(final Date date) {
        return formatDate(date, PATTERN_RFC1123);
    }

    /**
     * Formats the given date according to the specified pattern. The pattern
     * must conform to that used by the {@link SimpleDateFormat simple date
     * format} class.
     * 
     * @param date
     *            The date to format.
     * @param pattern
     *            The pattern to use for formatting the date.
     * @return A formatted date string.
     * 
     * @throws IllegalArgumentException
     *             If the given date pattern is invalid.
     * 
     * @see SimpleDateFormat
     */
    public static String formatDate(final Date date, final String pattern) {
        if (date == null)
            throw new IllegalArgumentException("date is null");
        if (pattern == null)
            throw new IllegalArgumentException("pattern is null");

        final SimpleDateFormat formatter = new SimpleDateFormat(pattern,
                Locale.US);
        formatter.setTimeZone(GMT);
        return formatter.format(date);
    }

    /**
     * If an HttpObject implements the market interface LastHttpContent, it
     * represents the last chunk of a transfer.
     * 
     * @see io.netty.handler.codec.http.LastHttpContent
     * 
     * @param httpObject
     * @return
     * 
     */
    public static boolean isLastChunk(final HttpObject httpObject) {
        return httpObject instanceof LastHttpContent;
    }

    /**
     * If an HttpObject is not the last chunk, then that means there are other
     * chunks that will follow.
     * 
     * @see io.netty.handler.codec.http.FullHttpMessage
     * 
     * @param httpObject
     * @return
     */
    public static boolean isChunked(final HttpObject httpObject) {
        return !isLastChunk(httpObject);
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 
     * @param httpRequest
     *            The request.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final HttpRequest httpRequest) {
        final String uriHostAndPort = parseHostAndPort(httpRequest.getUri());
        return uriHostAndPort;
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 
     * @param uri
     *            The URI.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final String uri) {
        final String tempUri;
        if (!HTTP_PREFIX.matcher(uri).matches()) {
            // Browsers particularly seem to send requests in this form when
            // they use CONNECT.
            tempUri = uri;
        } else {
            // We can't just take a substring from a hard-coded index because it
            // could be either http or https.
            tempUri = StringUtils.substringAfter(uri, "://");
        }
        final String hostAndPort;
        if (tempUri.contains("/")) {
            hostAndPort = tempUri.substring(0, tempUri.indexOf("/"));
        } else {
            hostAndPort = tempUri;
        }
        return hostAndPort;
    }

    /**
     * Make a copy of the response including all mutable fields.
     * 
     * @param original
     *            The original response to copy from.
     * @return The copy with all mutable fields from the original.
     */
    public static HttpResponse copyMutableResponseFields(
            final HttpResponse original) {

        HttpResponse copy = null;
        if (original instanceof DefaultFullHttpResponse) {
            ByteBuf content = ((DefaultFullHttpResponse) original).content();
            copy = new DefaultFullHttpResponse(original.getProtocolVersion(),
                    original.getStatus(), content);
        } else {
            copy = new DefaultHttpResponse(original.getProtocolVersion(),
                    original.getStatus());
        }
        final Collection<String> headerNames = original.headers().names();
        for (final String name : headerNames) {
            final List<String> values = original.headers().getAll(name);
            copy.headers().set(name, values);
        }
        return copy;
    }

    /**
     * Adds the Via header to specify that the message has passed through the proxy. The specified alias will be
     * appended to the Via header line. The alias may be the hostname of the machine proxying the request, or a
     * pseudonym. From RFC 7230, section 5.7.1:
     * <pre>
         The received-by portion of the field value is normally the host and
         optional port number of a recipient server or client that
         subsequently forwarded the message.  However, if the real host is
         considered to be sensitive information, a sender MAY replace it with
         a pseudonym.
     * </pre>
     *
     * 
     * @param httpMessage HTTP message to add the Via header to
     * @param alias the alias to provide in the Via header for this proxy
     */
    public static void addVia(HttpMessage httpMessage, String alias) {
        String newViaHeader =  new StringBuilder()
                .append(httpMessage.getProtocolVersion().majorVersion())
                .append('.')
                .append(httpMessage.getProtocolVersion().minorVersion())
                .append(' ')
                .append(alias)
                .toString();

        final List<String> vias;
        if (httpMessage.headers().contains(HttpHeaders.Names.VIA)) {
            List<String> existingViaHeaders = httpMessage.headers().getAll(HttpHeaders.Names.VIA);
            vias = new ArrayList<String>(existingViaHeaders);
            vias.add(newViaHeader);
        } else {
            vias = Collections.singletonList(newViaHeader);
        }

        httpMessage.headers().set(HttpHeaders.Names.VIA, vias);
    }

    /**
     * Returns <code>true</code> if the specified string is either "true" or
     * "on" ignoring case.
     * 
     * @param val
     *            The string in question.
     * @return <code>true</code> if the specified string is either "true" or
     *         "on" ignoring case, otherwise <code>false</code>.
     */
    public static boolean isTrue(final String val) {
        return checkTrueOrFalse(val, "true", "on");
    }

    /**
     * Returns <code>true</code> if the specified string is either "false" or
     * "off" ignoring case.
     * 
     * @param val
     *            The string in question.
     * @return <code>true</code> if the specified string is either "false" or
     *         "off" ignoring case, otherwise <code>false</code>.
     */
    public static boolean isFalse(final String val) {
        return checkTrueOrFalse(val, "false", "off");
    }

    public static boolean extractBooleanDefaultFalse(final Properties props,
            final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return false;
    }

    public static boolean extractBooleanDefaultTrue(final Properties props,
            final String key) {
        final String throttle = props.getProperty(key);
        if (StringUtils.isNotBlank(throttle)) {
            return throttle.trim().equalsIgnoreCase("true");
        }
        return true;
    }
    
    public static int extractInt(final Properties props, final String key) {
        return extractInt(props, key, -1);
    }
    
    public static int extractInt(final Properties props, final String key, int defaultValue) {
        final String readThrottleString = props.getProperty(key);
        if (StringUtils.isNotBlank(readThrottleString) &&
            NumberUtils.isNumber(readThrottleString)) {
            return Integer.parseInt(readThrottleString);
        }
        return defaultValue;
    }

    public static boolean isCONNECT(HttpObject httpObject) {
        return httpObject instanceof HttpRequest
                && HttpMethod.CONNECT.equals(((HttpRequest) httpObject)
                        .getMethod());
    }

    /**
     * Returns true if the specified HttpRequest is a HEAD request.
     *
     * @param httpRequest http request
     * @return true if request is a HEAD, otherwise false
     */
    public static boolean isHEAD(HttpRequest httpRequest) {
        return HttpMethod.HEAD.equals(httpRequest.getMethod());
    }

    private static boolean checkTrueOrFalse(final String val,
            final String str1, final String str2) {
        final String str = val.trim();
        return StringUtils.isNotBlank(str)
                && (str.equalsIgnoreCase(str1) || str.equalsIgnoreCase(str2));
    }

    /**
     * Returns true if the HTTP message cannot contain an entity body, according to the HTTP spec. This code is taken directly
     * from {@link io.netty.handler.codec.http.HttpObjectDecoder#isContentAlwaysEmpty(HttpMessage)}.
     *
     * @param msg HTTP message
     * @return true if the HTTP message is always empty, false if the message <i>may</i> have entity content.
     */
    public static boolean isContentAlwaysEmpty(HttpMessage msg) {
        if (msg instanceof HttpResponse) {
            HttpResponse res = (HttpResponse) msg;
            int code = res.getStatus().code();

            // Correctly handle return codes of 1xx.
            //
            // See:
            //     - http://www.w3.org/Protocols/rfc2616/rfc2616-sec4.html Section 4.4
            //     - https://github.com/netty/netty/issues/222
            if (code >= 100 && code < 200) {
                // According to RFC 7231, section 6.1, 1xx responses have no content (https://tools.ietf.org/html/rfc7231#section-6.2):
                //   1xx responses are terminated by the first empty line after
                //   the status-line (the empty line signaling the end of the header
                //        section).

                // Hixie 76 websocket handshake responses contain a 16-byte body, so their content is not empty; but Hixie 76
                // was a draft specification that was superceded by RFC 6455. Since it is rarely used and doesn't conform to
                // RFC 7231, we do not support or make special allowance for Hixie 76 responses.
                return true;
            }

            switch (code) {
                case 204: case 205: case 304:
                    return true;
            }
        }
        return false;
    }

    /**
     * Returns true if the request is an HTTP HEAD request.
     *
     * @param request HTTP request
     * @return true if request is a HEAD, otherwise false
     */
    public static boolean isHead(HttpRequest request) {
        return HttpMethod.HEAD.equals(request.getMethod());
    }

    /**
     * Returns true if the HTTP response from the server is expected to indicate its own message length/end-of-message. Returns false
     * if the server is expected to indicate the end of the HTTP entity by closing the connection.
     * <p/>
     * This method is based on the allowed message length indicators in the HTTP specification, section 4.4:
     * <pre>
         4.4 Message Length
         The transfer-length of a message is the length of the message-body as it appears in the message; that is, after any transfer-codings have been applied. When a message-body is included with a message, the transfer-length of that body is determined by one of the following (in order of precedence):

         1.Any response message which "MUST NOT" include a message-body (such as the 1xx, 204, and 304 responses and any response to a HEAD request) is always terminated by the first empty line after the header fields, regardless of the entity-header fields present in the message.
         2.If a Transfer-Encoding header field (section 14.41) is present and has any value other than "identity", then the transfer-length is defined by use of the "chunked" transfer-coding (section 3.6), unless the message is terminated by closing the connection.
         3.If a Content-Length header field (section 14.13) is present, its decimal value in OCTETs represents both the entity-length and the transfer-length. The Content-Length header field MUST NOT be sent if these two lengths are different (i.e., if a Transfer-Encoding
         header field is present). If a message is received with both a Transfer-Encoding header field and a Content-Length header field, the latter MUST be ignored.
         [LP note: multipart/byteranges support has been removed from the HTTP 1.1 spec by RFC 7230, section A.2. Since it is seldom used, LittleProxy does not check for it.]
         5.By the server closing the connection. (Closing the connection cannot be used to indicate the end of a request body, since that would leave no possibility for the server to send back a response.)
     * </pre>
     *
     * The rules for Transfer-Encoding are clarified in RFC 7230, section 3.3.1 and 3.3.3 (3):
     * <pre>
         If any transfer coding other than
         chunked is applied to a response payload body, the sender MUST either
         apply chunked as the final transfer coding or terminate the message
         by closing the connection.
     * </pre>
     *
     *
     * @param response the HTTP response object
     * @return true if the message will indicate its own message length, or false if the server is expected to indicate the message length by closing the connection
     */
    public static boolean isResponseSelfTerminating(HttpResponse response) {
        if (isContentAlwaysEmpty(response)) {
            return true;
        }

        // if there is a Transfer-Encoding value, determine whether the final encoding is "chunked", which makes the message self-terminating
        List<String> allTransferEncodingHeaders = getAllCommaSeparatedHeaderValues(HttpHeaders.Names.TRANSFER_ENCODING, response);
        if (!allTransferEncodingHeaders.isEmpty()) {
            String finalEncoding = allTransferEncodingHeaders.get(allTransferEncodingHeaders.size() - 1);

            // per #3 above: "If a message is received with both a Transfer-Encoding header field and a Content-Length header field, the latter MUST be ignored."
            // since the Transfer-Encoding field is present, the message is self-terminating if and only if the final Transfer-Encoding value is "chunked"
            return HttpHeaders.Values.CHUNKED.equals(finalEncoding);
        }

        String contentLengthHeader = HttpHeaders.getHeader(response, HttpHeaders.Names.CONTENT_LENGTH);
        if (contentLengthHeader != null && !contentLengthHeader.isEmpty()) {
            return true;
        }

        // not checking for multipart/byteranges, since it is seldom used and its use as a message length indicator was removed in RFC 7230

        // none of the other message length indicators are present, so the only way the server can indicate the end
        // of this message is to close the connection
        return false;
    }

    /**
     * Retrieves all comma-separated values for headers with the specified name on the HttpMessage. Any whitespace (spaces
     * or tabs) surrounding the values will be removed. Empty values (e.g. two consecutive commas, or a value followed
     * by a comma and no other value) will be removed; they will not appear as empty elements in the returned list.
     * If the message contains repeated headers, their values will be added to the returned list in the order in which
     * the headers appear. For example, if a message has headers like:
     * <pre>
     *     Transfer-Encoding: gzip,deflate
     *     Transfer-Encoding: chunked
     * </pre>
     * This method will return a list of three values: "gzip", "deflate", "chunked".
     * <p/>
     * Placing values on multiple header lines is allowed under certain circumstances
     * in RFC 2616 section 4.2, and in RFC 7230 section 3.2.2 quoted here:
     * <pre>
     A sender MUST NOT generate multiple header fields with the same field
     name in a message unless either the entire field value for that
     header field is defined as a comma-separated list [i.e., #(values)]
     or the header field is a well-known exception (as noted below).

     A recipient MAY combine multiple header fields with the same field
     name into one "field-name: field-value" pair, without changing the
     semantics of the message, by appending each subsequent field value to
     the combined field value in order, separated by a comma.  The order
     in which header fields with the same field name are received is
     therefore significant to the interpretation of the combined field
     value; a proxy MUST NOT change the order of these field values when
     forwarding a message.
     * </pre>
     * @param headerName the name of the header for which values will be retrieved
     * @param httpMessage the HTTP message whose header values will be retrieved
     * @return a list of single header values, or an empty list if the header was not present in the message or contained no values
     */
    public static List<String> getAllCommaSeparatedHeaderValues(String headerName, HttpMessage httpMessage) {
        List<String> allHeaders = httpMessage.headers().getAll(headerName);
        if (allHeaders.isEmpty()) {
            return Collections.emptyList();
        }

        ImmutableList.Builder<String> headerValues = ImmutableList.builder();
        for (String header : allHeaders) {
            List<String> commaSeparatedValues = splitCommaSeparatedHeaderValues(header);
            headerValues.addAll(commaSeparatedValues);
        }

        return headerValues.build();
    }

    /**
     * Duplicates the status line and headers of an HttpResponse object. Does not duplicate any content associated with that response.
     *
     * @param originalResponse HttpResponse to be duplicated
     * @return a new HttpResponse with the same status line and headers
     */
    public static HttpResponse duplicateHttpResponse(HttpResponse originalResponse) {
        DefaultHttpResponse newResponse = new DefaultHttpResponse(originalResponse.getProtocolVersion(), originalResponse.getStatus());
        newResponse.headers().add(originalResponse.headers());

        return newResponse;
    }

    /**
     * Attempts to resolve the local machine's hostname.
     *
     * @return the local machine's hostname, or null if a hostname cannot be determined
     */
    public static String getHostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (IOException e) {
            LOG.debug("Ignored exception", e);
        } catch (RuntimeException e) {
            // An exception here must not stop the proxy. Android could throw a
            // runtime exception, since it not allows network access in the main
            // process.
            LOG.debug("Ignored exception", e);
        }
        LOG.info("Could not lookup localhost");
        return null;
    }

    /**
     * Determines if the specified header should be removed from the proxied response because it is a hop-by-hop header, as defined by the
     * HTTP 1.1 spec in section 13.5.1. The comparison is case-insensitive, so "Connection" will be treated the same as "connection" or "CONNECTION".
     * From http://www.w3.org/Protocols/rfc2616/rfc2616-sec13.html#sec13.5.1 :
     * <pre>
       The following HTTP/1.1 headers are hop-by-hop headers:
        - Connection
        - Keep-Alive
        - Proxy-Authenticate
        - Proxy-Authorization
        - TE
        - Trailers [LittleProxy note: actual header name is Trailer]
        - Transfer-Encoding [LittleProxy note: this header is not normally removed when proxying, since the proxy does not re-chunk
                            responses. The exception is when an HttpObjectAggregator is enabled, which aggregates chunked content and removes
                            the 'Transfer-Encoding: chunked' header itself.]
        - Upgrade

       All other headers defined by HTTP/1.1 are end-to-end headers.
     * </pre>
     *
     * @param headerName the header name
     * @return true if this header is a hop-by-hop header and should be removed when proxying, otherwise false
     */
    public static boolean shouldRemoveHopByHopHeader(String headerName) {
        return SHOULD_NOT_PROXY_HOP_BY_HOP_HEADERS.contains(headerName.toLowerCase(Locale.US));
    }

    /**
     * Splits comma-separated header values into tokens. For example, if the value of the Connection header is "Transfer-Encoding, close",
     * this method will return "Transfer-Encoding" and "close". This method strips trims any optional whitespace from
     * the tokens. Unlike {@link #getAllCommaSeparatedHeaderValues(String, HttpMessage)}, this method only operates on
     * a single header value, rather than all instances of the header in a message.
     *
     * @param headerValue the un-tokenized header value (must not be null)
     * @return all tokens within the header value, or an empty list if there are no values
     */
    public static List<String> splitCommaSeparatedHeaderValues(String headerValue) {
        return ImmutableList.copyOf(COMMA_SEPARATED_HEADER_VALUE_SPLITTER.split(headerValue));
    }

    /**
     * Determines if UDT is available on the classpath.
     *
     * @return true if UDT is available
     */
    public static boolean isUdtAvailable() {
        try {
            return NioUdtProvider.BYTE_PROVIDER != null;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }
}
