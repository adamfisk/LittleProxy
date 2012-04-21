package org.littleshoot.proxy;

import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.timeout.IdleStateEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Idle handler that monitors requests that haven't resolved
 * on browser to proxy connections. Useful for debugging 
 * slow requests.
 */
public class IdleRequestHandler extends IdleAwareHandler {

    private final HttpRequestHandler handler;

    private final Logger log = LoggerFactory.getLogger(getClass());

    public IdleRequestHandler(final HttpRequestHandler handler) {
        super("Client-Pipeline");
        this.handler = handler;
    }

    @Override
    public void channelIdle(final ChannelHandlerContext ctx, 
        final IdleStateEvent e) {
        super.channelIdle(ctx, e);
        final Set<HttpRequest> unansweredHttpRequests = handler
                .getUnansweredHttpRequests();
        // If this isn't empty, we have requests paused
        if (!unansweredHttpRequests.isEmpty()) {
            final StringBuilder message = new StringBuilder(
                "The connection was terminated before resolving the following resources:\n");
            for (HttpRequest unansweredRequest : unansweredHttpRequests) {
                // Go through each unanswered request and concat the info
                message.append(unansweredRequest.getUri());
                String referrer = unansweredRequest.getHeader("Referer");
                if (!StringUtils.isBlank(referrer)) {
                    // Capure the referrer so that slow resources can be tracked
                    // to a page
                    message.append(" from ").append(referrer);
                }
                message.append("\n");
            }
            // Log all of the requests that failed
            log.error(message.toString(), new IdleHttpRequestException());
        }
    }
}
