Resources
---------

http://netty.io/news/2013/06/18/4-0-0-CR5.html
http://netty.io/4.0/api
http://docs.jboss.org/netty/3.2/api/


Relevant Changes
----------------
* ServerBootstrap no longer takes a ServerSocketChannelFactory in its
  constructor.  We used to use this to configure the thread pools.  Looks like
  EventLoopGroup or its relatives are the place to look.

* Same thing as #1 is true for ClientSocketChannelFactory.

* ChannelPipelineFactory is gone.  Looks like one uses .childHandler() with a
  ChannelInitializer instead.

* What's up with ThreadRenamingRunnable in DefaultHttpProxyServer?

* DefaultChannelGroup?

* SimpleChannelUpstreamHandler -> SimpleChanneInboundHandler

* InterestOps is gone - what does this mean to setReadable() and
  channelInterestChanged()  ?

* IdleStateHandler no longer users Timer.  This may be a problem, because it's
  just scheduling things on an EventExecutor obtained from the underlying
  EventLoopGroup.  That infrastructure doesn't appear to use Timers at all. 

* messageReceived() -> channelRead0 with POJO typed message

* HttpChunk -> HttpObject

* HttpChunkAggregator -> HttpObjectAggregator

* Channel lifecycle is different:
  * old: open -> bound -> connected
  * new: open -> registered -> active   ??
  
* channelOpen() -> channelRegistered() ?  note that we added call to super

* channelClosed() -> channelUnregistered() ?   note that we added call to super

* lifecycle callbacks no longer get a ChannelStateEvent.  We often grabbed the
  channel from the event, we now grab it from the ChannelHandlerContext.
  
* IdleStateAwareChannelHandler -> ChannelDuplexHandler

* ChannelBuffer -> ByteBuf

* Headers on HttpObject are no longer accessed directly, but first by getting
  the headers object using .headers()
  
* ChannelBuffers -> Unpooled