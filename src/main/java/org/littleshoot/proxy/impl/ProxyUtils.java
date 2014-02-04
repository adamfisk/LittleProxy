package org.littleshoot.proxy.impl;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMessage;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.LastHttpContent;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.TimeZone;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.math.NumberUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for the proxy.
 */
public class ProxyUtils {

    private static final Logger LOG = LoggerFactory.getLogger(ProxyUtils.class);

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");

    /**
     * Date format pattern used to parse HTTP date headers in RFC 1123 format.
     */
    private static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    private static final String hostName;

    static {
        try {
            final InetAddress localAddress = NetworkUtils.getLocalHost();
            hostName = localAddress.getHostName();
        } catch (final UnknownHostException e) {
            LOG.error("Could not lookup host", e);
            throw new IllegalStateException("Could not determine host!", e);
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("Via: 1.1 ");
        sb.append(hostName);
        sb.append("\r\n");
    }

    // Should never be constructed.
    private ProxyUtils() {
    }

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
     * Creates a Date formatted for HTTP headers for the current time.
     * 
     * @return The formatted HTTP date.
     */
    public static String httpDate() {
        return formatDate(new Date());
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
     * Adds the Via header to specify that the message has passed through the
     * proxy.
     * 
     * @param msg
     *            The HTTP message.
     */
    public static void addVia(final HttpMessage msg) {
        final StringBuilder sb = new StringBuilder();
        sb.append(msg.getProtocolVersion().majorVersion());
        sb.append(".");
        sb.append(msg.getProtocolVersion().minorVersion());
        sb.append(".");
        sb.append(hostName);
        final List<String> vias;
        if (msg.headers().contains(HttpHeaders.Names.VIA)) {
            vias = msg.headers().getAll(HttpHeaders.Names.VIA);
            vias.add(sb.toString());
        } else {
            vias = Arrays.asList(sb.toString());
        }
        msg.headers().set(HttpHeaders.Names.VIA, vias);
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

    private static boolean checkTrueOrFalse(final String val,
            final String str1, final String str2) {
        final String str = val.trim();
        return StringUtils.isNotBlank(str)
                && (str.equalsIgnoreCase(str1) || str.equalsIgnoreCase(str2));
    }

}
