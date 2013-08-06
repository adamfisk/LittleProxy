Resources
---------

http://netty.io/news/2013/06/18/4-0-0-CR5.html
http://netty.io/4.0/api
http://docs.jboss.org/netty/3.2/api/


Relevant Changes
----------------
1. ServerBootstrap no longer takes a ServerSocketChannelFactory in its constructor.  We used to use this to configure the thread pools.  Looks like EventLoopGroup or its relatives are the place to look.

2. Same thing as #1 is true for ClientSocketChannelFactory.

3. ChannelPipelineFactory is gone.  Looks like one uses .childHandler() with a ChannelInitializer instead.

4. What's up with ThreadRenamingRunnable in DefaultHttpProxyServer?

5. DefaultChannelGroup?

6. SimpleChannelUpstreamHandler -> SimpleChanneInboundHandler

7. InterestOps is gone - what does this mean to setReadable() and channelInterestChanged()  ?

8. channelOpened() -> channelRegistered() ?

9. channelClosed() -> channelDeregistered() ?