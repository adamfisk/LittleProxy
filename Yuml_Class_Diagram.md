// This code generates a UML diagram on yuml.me.  The diagram's purpose is mostly to understand dependencies
// in order to facilitate the upgrade from Netty 3.x to 4.x.  It is neither a precise, complete nor accurate representation
// of the object model.
// Green background - Netty classes that appear to be carrying over from 3.x to 4.x
// Red background - Netty classes that were lost in 4.x
// Orange background - Netty classes introduced in 4.x that replace a class from 3.x




[<<HttpProxyServer>>]^-.-[DefaultHttpProxyServer]
[DefaultHttpProxyServer]creates ->[<<ProxyAuthorizationManager>>]
[<<ProxyAuthorizationManager>>]^-.-[DefaultProxyAuthorizationManager]
[DefaultHttpProxyServer]creates ->[<<ChainProxyManager>>]
[DefaultHttpProxyServer]creates ->[<<HandshakeHandlerFactory>>]
[<<HandshakeHandlerFactory>>]^-.-[SslHandshakeHandlerFactory]
[<<HandshakeHandlerFactory>>]^-.-[SelfSignedSslHandshakeHandlerFactory]
[DefaultHttpProxyServer]creates ->[<<HttpRequestFilter>>]
[<<HttpRequestFilter>>]^-.-[PublicIpsOnlyRequestFilter]
[<<HttpRequestFilter>>]^-.-[RegexHttpRequestFilter]
[<<HttpRequestFilter>>]^-.-[ProxyUtils.PASS_THROUGH_REQUEST_FILTER]
[DefaultHttpProxyServer]creates ->[<<HttpResponseFilters>>]
[DefaultHttpProxyServer]creates ->[<<ProxyCacheManager>>]
[<<ProxyCacheManager>>]^-.-[DefaultProxyCacheManager]
[<<ProxyCacheManager>>]^-.-[SimpleProxyCacheManager]
[<<ProxyCacheManager>>]^-.-[ProxyUtils.Noop Cache Manager]
[DefaultHttpProxyServer]creates ->[<<ChannelGroup>>{bg:green}]
[<<ChannelGroup>>]^-.-[DefaultChannelGroup]
[DefaultHttpProxyServer]creates ->[<<Timer>>{bg:green}]
[<<Timer>>]^-.-[HashedWheelTimer{bg:green}]
[DefaultHttpProxyServer]creates ->[<<ServerSocketChannelFactory>>{bg:red}]
[<<ServerSocketChannelFactory>>]^-.-[NioServerSocketChannelFactory{bg:red}]
[DefaultHttpProxyServer]creates ->[<<ClientSocketChannelFactory>>{bg:red}]
[<<ClientSocketChannelFactory>>]^-.-[NioClientSocketChannelFactory{bg:red}]
[<<ChannelFactory>>{bg:green}]^-.-[<<ServerSocketChannelFactory>>]
[<<ChannelFactory>>{bg:green}]^-.-[<<ClientSocketChannelFactory>>]
[DefaultHttpProxyServer]creates ->[ServerBootstrap{bg:green}]
[ServerBootstrap]->[<<ServerSocketChannelFactory>>]
[<<ServerSocketChannelFactory>>]superseded by ->[<<EventLoopGroup>>{bg:orange}]
[<<ClientSocketChannelFactory>>]superseded by ->[<<EventLoopGroup>>{bg:orange}]
[ServerBootstrap]->[<<ChannelPipelineFactory>>{bg:red}]
[<<ChannelPipelineFactory>>]^-.-[HttpServerPipelineFactory]
[<<ChannelPipelineFactory>>]superseded by ->[<<ChannelInitializer>>{bg:orange}]
// Next section - HttpServerPipelineFactory
[HttpServerPipelineFactory]->[<<ChannelGroup>>{bg:orange}]
[HttpServerPipelineFactory]->[<<ProxyAuthorizationManager>>]
[HttpServerPipelineFactory]->[<<ChainProxyManager>>]
[HttpServerPipelineFactory]->[<<HandshakeHandlerFactory>>]
[HttpServerPipelineFactory]->[<<RelayPipelineFactory>>]
[<<RelayPipelineFactory>>]^-.-[DefaultRelayPipelineFactory]
[<<RelayPipelineFactory]->[<<ChannelPipelineFactory>>]
[HttpServerPipelineFactory]->[<<Timer>>]
[HttpServerPipelineFactory]->[<<ClientSocketChannelFactory>>{bg:orange}]