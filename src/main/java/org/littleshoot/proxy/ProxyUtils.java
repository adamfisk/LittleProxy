package org.littleshoot.proxy;

import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedList;
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
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpChunk;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMessage;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;
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
    public static final String PATTERN_RFC1123 = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /**
     * Date format pattern used to parse HTTP date headers in RFC 1036 format.
     */
    public static final String PATTERN_RFC1036 = "EEEE, dd-MMM-yy HH:mm:ss zzz";
    
    private static final String via;
    private static final String hostName;
    
    static {
        try {
            final InetAddress localAddress = InetAddress.getLocalHost();
            hostName = localAddress.getHostName();
        }
        catch (final UnknownHostException e) {
            LOG.error("Could not lookup host", e);
            throw new IllegalStateException("Could not determine host!", e);
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("Via: 1.1 ");
        sb.append(hostName);
        sb.append("\r\n");
        via = sb.toString();
    }

    /**
     * Utility class for a no-op {@link ChannelFutureListener}.
     */
    public static final ChannelFutureListener NO_OP_LISTENER = 
        new ChannelFutureListener() {
        public void operationComplete(final ChannelFuture future) 
            throws Exception {
            LOG.info("No op listener - write finished");
        }
    };

    /**
     * Constant for the headers for an OK response to an HTTP connect request.
     */
    public static final String CONNECT_OK_HEADERS = 
        "Connection: Keep-Alive\r\n"+
        "Proxy-Connection: Keep-Alive\r\n"+
        via + 
        "\r\n";

    /**
     * Constant for the headers for a proxy error response.
     */
    public static final String PROXY_ERROR_HEADERS = 
        "Connection: close\r\n"+
        "Proxy-Connection: close\r\n"+
        "Pragma: no-cache\r\n"+
        "Cache-Control: no-cache\r\n" +
        via +
        "\r\n";
    
    // Should never be constructed.
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
     * Builds the cache URI from the request, including the host and the path.
     * 
     * @param httpRequest The request.
     * @return The cache URI.
     */
    public static String cacheUri(final HttpRequest httpRequest) {
        final String host = httpRequest.getHeader(HttpHeaders.Names.HOST);
        final String uri = httpRequest.getUri();
        final String path;
        if (uri.startsWith("http")) {
            path = stripHost(uri);
        }
        else {
            path = uri;
        }
        
        // TODO: We don't append http:// or https:// here. Is it really OK to
        // ignore the protocol?
        return host + path;
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
            printHeader(msg, name);
        }
    }

    /**
     * Prints the specified header from the specified method.
     * 
     * @param msg The HTTP message.
     * @param name The name of the header to print.
     */
    public static void printHeader(final HttpMessage msg, final String name) {
        final String value = msg.getHeader(name);
        LOG.debug(name + ": "+value);
    }

    /**
     * Creates a write listener for the given HTTP response. This is the 
     * listener that should be used after the response is written. If the
     * request is HTTP 1.0 with no keep-alive header, for example, the 
     * write listener would close the connection.
     * 
     * This in part determines if we should close the connection. Here's the  
     * relevant section of RFC 2616:
     * 
     * "HTTP/1.1 defines the "close" connection option for the sender to 
     * signal that the connection will be closed after completion of the 
     * response. For example,
     * 
     * Connection: close
     * 
     * in either the request or the response header fields indicates that the 
     * connection SHOULD NOT be considered `persistent' (section 8.1) after 
     * the current request/response is complete."
     * @param future 
     * 
     * @param httpRequest The original HTTP request. 
     * @param httpResponse The HTTP response.
     * @param msg The HTTP message. This will be identical to the 
     * second argument except in the case of chunked responses, where this
     * could be an HTTP chunk. 
     */
    public static void addListenerForResponse(
        final ChannelFuture future, final HttpRequest httpRequest, 
        final HttpResponse httpResponse, final Object msg) {
        final ChannelFutureListener cfl = 
            newWriteListener(httpRequest, httpResponse, msg);
        if (cfl != ProxyUtils.NO_OP_LISTENER) {
            future.addListener(cfl);
        }
    }

    /**
     * Adds a listener for the given response when we know all the response
     * body data is complete.
     * 
     * @param future The future to add the listener to.
     * @param httpRequest The original request.
     * @param httpResponse The original response.
     */
    public static void addListenerForCompleteResponse(
        final ChannelFuture future, final HttpRequest httpRequest, 
        final HttpResponse httpResponse) {
        if (!HttpHeaders.isKeepAlive(httpRequest)) {
            LOG.info("Closing since request is not keep alive:");
            ProxyUtils.printHeaders(httpRequest);
            future.addListener(ChannelFutureListener.CLOSE);
        }
        if (!HttpHeaders.isKeepAlive(httpResponse)) {
            LOG.info("Closing since response is not keep alive:");
            ProxyUtils.printHeaders(httpResponse);
            future.addListener(ChannelFutureListener.CLOSE);
        }
    }

    /**
     * Creates a new write listener for the given request and response pair.
     * 
     * @param httpRequest The HTTP request.
     * @param httpResponse The HTTP response.
     * @param msg The real message being written. This can be the HTTP message,
     * and it can also be a chunk.
     * @return The new listener.
     */
    public static ChannelFutureListener newWriteListener(
        final HttpRequest httpRequest, final HttpResponse httpResponse, 
        final Object msg) {
        if (httpResponse.isChunked()) {
            // If the response is chunked, we want to return unless it's
            // the last chunk. If it is the last chunk, then we want to pass
            // through to the same close semantics we'd otherwise use.
            if (msg != null) {
                if (!isLastChunk(msg)) {
                    LOG.info("Using no-op listener on middle chunk");
                    return ProxyUtils.NO_OP_LISTENER;
                }
                else {
                    LOG.info("Last chunk...using normal closing rules");
                }
            }
        }
        if (!HttpHeaders.isKeepAlive(httpRequest)) {
            LOG.info("Using close listener since request is not keep alive:");
            ProxyUtils.printHeaders(httpRequest);
            return ChannelFutureListener.CLOSE;
        }
        if (!HttpHeaders.isKeepAlive(httpResponse)) {
            LOG.info("Using close listener since response is not keep alive:");
            ProxyUtils.printHeaders(httpResponse);
            return ChannelFutureListener.CLOSE;
        }
        LOG.info("Using no-op listener...");
        return ProxyUtils.NO_OP_LISTENER;
    }

    private static boolean isLastChunk(final Object msg) {
        if (msg instanceof HttpChunk) {
            final HttpChunk chunk = (HttpChunk) msg;
            return chunk.isLast();
        } else {
            return false;
        }
    }

    /**
     * Parses the host and port an HTTP request is being sent to.
     * 
     * @param httpRequest The request.
     * @return The host and port string.
     */
    public static String parseHostAndPort(final HttpRequest httpRequest) {
        final String uri = httpRequest.getUri();
        final String tempUri;
        if (!uri.startsWith("http")) {
            // Browsers particularly seem to send requests in this form when
            // they use CONNECT.
            tempUri = uri;
        }
        else {
            // We can't just take a substring from a hard-coded index because it
            // could be either http or https.
            tempUri = StringUtils.substringAfter(uri, "://");
        }
        final String hostAndPort;
        if (tempUri.contains("/")) {
            hostAndPort = tempUri.substring(0, tempUri.indexOf("/"));
        }
        else {
            hostAndPort = tempUri;
        }
        return hostAndPort;
    }
    
    /**
     * Parses the port from an address.
     * 
     * @param httpRequest The request containing the URI.
     * @return The port. If not port is explicitly specified, returns the 
     * the default port 80 if the protocol is HTTP and 443 if the protocol is
     * HTTPS.
     */
    public static int parsePort(final HttpRequest httpRequest) {
        final String uri = httpRequest.getUri();
        if (uri.contains(":")) {
            final String portStr = StringUtils.substringAfter(uri, ":"); 
            return Integer.parseInt(portStr);
        }
        else if (uri.startsWith("http")) {
            return 80;
        }
        else if (uri.startsWith("https")) {
            return 443;
        }
        else {
            // Unsupported protocol -- return 80 for now.
            return 80;
        }
    }

    /**
     * Creates a copy of an original HTTP request to void modifying it.
     * 
     * @param original The original request.
     * @return The request copy.
     */
    public static HttpRequest copyHttpRequest(final HttpRequest original) {
        final HttpMethod method = original.getMethod();
        final String uri = original.getUri();
        LOG.info("Raw URI before switching from proxy format: {}", uri);
        final String noHostUri = ProxyUtils.stripHost(uri);
        final HttpRequest copy = 
            new DefaultHttpRequest(original.getProtocolVersion(), 
                method, noHostUri);
        
        final ChannelBuffer originalContent = original.getContent();
        
        if (originalContent != null) {
            copy.setContent(originalContent);
        }
        
        LOG.info("Request copy method: {}", copy.getMethod());
        final Set<String> headerNames = original.getHeaderNames();
        for (final String name : headerNames) {
            final List<String> values = original.getHeaders(name);
            copy.setHeader(name, values);
        }
        final String ae = copy.getHeader(HttpHeaders.Names.ACCEPT_ENCODING);
        if (StringUtils.isNotBlank(ae)) {
            // Remove sdch from encodings we accept since we can't decode it.
            final String noSdch = ae.replace(",sdch", "").replace("sdch", "");
            copy.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, noSdch);
            LOG.info("Removed sdch and inserted: {}", noSdch);
        }
        
        // Switch the de-facto standard "Proxy-Connection" header to 
        // "Connection" when we pass it along to the remote host.
        final String proxyConnectionKey = "Proxy-Connection";
        if (copy.containsHeader(proxyConnectionKey)) {
            final String header = copy.getHeader(proxyConnectionKey);
            copy.removeHeader(proxyConnectionKey);
            copy.setHeader("Connection", header);
        }
        
        ProxyUtils.addVia(copy);
        return copy;
    }

    /**
     * Adds the Via header to specify that's the request has passed through
     * the proxy.
     * 
     * @param httpRequest The request.
     */
    public static void addVia(final HttpRequest httpRequest) {
        final List<String> vias; 
        if (httpRequest.containsHeader(HttpHeaders.Names.VIA)) {
            vias = httpRequest.getHeaders(HttpHeaders.Names.VIA);
        }
        else {
            vias = new LinkedList<String>();
        }
        
        final StringBuilder sb = new StringBuilder();
        sb.append(httpRequest.getProtocolVersion().getMajorVersion());
        sb.append(".");
        sb.append(httpRequest.getProtocolVersion().getMinorVersion());
        sb.append(".");
        sb.append(hostName);
        vias.add(sb.toString());
        httpRequest.setHeader(HttpHeaders.Names.VIA, vias);
    }
}
