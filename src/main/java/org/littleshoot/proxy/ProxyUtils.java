package org.littleshoot.proxy;

import java.io.UnsupportedEncodingException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for the proxy.
 */
public class ProxyUtils {
    
    public static final Logger LOG = LoggerFactory.getLogger(ProxyUtils.class);

    private static final TimeZone GMT = TimeZone.getTimeZone("GMT");
    
    /**
     * Date format pattern used to parse HTTP date headers in RFC 1123 format.
     */
    public static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /**
     * Date format pattern used to parse HTTP date headers in RFC 1036 format.
     */
    public static final String PATTERN_RFC1036 = "EEEE, dd-MMM-yy HH:mm:ss zzz";

    /**
     * Utility class for a no-op {@link ChannelFutureListener}.
     */
    public static final ChannelFutureListener NO_OP_LISTENER = 
        new ChannelFutureListener() {
        public void operationComplete(final ChannelFuture future) 
            throws Exception {
        }
    };
    
    private ProxyUtils() {
    }
    

    /**
     * Strips the host from a URI string. This will turn "http://host.com/path"
     * into "/path".
     * 
     * @param uri The URI to transform.
     * @return A string with the URI stripped.
     */
    public static String stripHost(final String uri) {
        if (!uri.startsWith("http")) {
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
     * @param date The date to format.
     * @return An RFC 1123 formatted date string.
     * 
     * @see #PATTERN_RFC1123
     */
    public static String formatDate(final Date date) {
        return formatDate(date, PATTERN_RFC1123);
    }
    
    /**
     * Formats the given date according to the specified pattern.  The pattern
     * must conform to that used by the {@link SimpleDateFormat simple date
     * format} class.
     * 
     * @param date The date to format.
     * @param pattern The pattern to use for formatting the date.  
     * @return A formatted date string.
     * 
     * @throws IllegalArgumentException If the given date pattern is invalid.
     * 
     * @see SimpleDateFormat
     */
    public static String formatDate(final Date date, final String pattern) {
        if (date == null) 
            throw new IllegalArgumentException("date is null");
        if (pattern == null) 
            throw new IllegalArgumentException("pattern is null");
        
        final SimpleDateFormat formatter = 
            new SimpleDateFormat(pattern, Locale.US);
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
     * Copies the mutable fields from the response original to the copy.
     * 
     * @param original The original response to copy from.
     * @param copy The copy.
     * @return The copy with all mutable fields from the original.
     */
    public static HttpResponse copyMutableResponseFields(
        final HttpResponse original, final HttpResponse copy) {
        
        final Collection<String> headerNames = original.getHeaderNames();
        for (final String name : headerNames) {
            final List<String> values = original.getHeaders(name);
            copy.setHeader(name, values);
        }
        copy.setContent(original.getContent());
        return copy;
    }


    /**
     * Writes a raw HTTP response to the channel. 
     * 
     * @param channel The channel.
     * @param statusLine The status line of the response.
     * @param headers The raw headers string.
     */
    public static void writeResponse(final Channel channel,
        final String statusLine, final String headers) {
        writeResponse(channel, statusLine, headers, "");
    }

    /**
     * Writes a raw HTTP response to the channel. 
     * 
     * @param channel The channel.
     * @param statusLine The status line of the response.
     * @param headers The raw headers string.
     * @param responseBody The response body.
     */
    public static void writeResponse(final Channel channel, 
        final String statusLine, final String headers, 
        final String responseBody) {
        final String fullResponse = statusLine + headers + responseBody;
        LOG.info("Writing full response:\n"+fullResponse);
        try {
            final ChannelBuffer buf = 
                ChannelBuffers.copiedBuffer(fullResponse.getBytes("UTF-8"));
            channel.write(buf);
            channel.setReadable(true);
            return;
        }
        catch (final UnsupportedEncodingException e) {
            // Never.
            return;
        }    
    }

    /**
     * Prints the headers of the message (for debugging).
     * 
     * @param msg The {@link HttpMessage}.
     */
    public static void printHeaders(final HttpMessage msg) {
        final String status = msg.getProtocolVersion().toString();
        LOG.debug(status);
        final Set<String> headerNames = msg.getHeaderNames();
        for (final String name : headerNames) {
            final String value = msg.getHeader(name);
            //System.out.println(name + ": "+value);
            LOG.debug(name + ": "+value);
        }
    }
}
