package org.littleshoot.proxy;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpRequest;

/**
 * Interface for classes that manage proxy authentication.
 */
public interface ProxyAuthorizationManager {

    /**
     * Adds the specified {@link ProxyAuthorizationHandler}.
     * 
     * @param pah The {@link ProxyAuthorizationHandler} to add.
     */
    void addHandler(ProxyAuthorizationHandler pah);

    /**
     * Handles all aspects of authorizing the specified request, looping
     * through all registered {@link ProxyAuthorizationHandler}s.
     * 
     * @param httpRequest The HTTP request.
     * @param ctx The context, including the underlying channel.
     * @return <code>true</code> if authorization succeeded, otherwise 
     * <code>false</code>.
     */
    boolean handleProxyAuthorization(HttpRequest httpRequest,
        ChannelHandlerContext ctx);

}
