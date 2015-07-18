[![Build Status](https://travis-ci.org/adamfisk/LittleProxy.png?branch=master)](https://travis-ci.org/adamfisk/LittleProxy)

LittleProxy is a high performance HTTP proxy written in Java atop Trustin Lee's excellent [Netty](netty.io) event-based networking library. It's quite stable, performs well, and is easy to integrate into your projects. 

One option is to clone LittleProxy and run it from the command line. This is as simple as:

```
$ git clone git://github.com/adamfisk/LittleProxy.git
$ cd LittleProxy
$ ./run.bash
```

You can embed LittleProxy in your own projects through Maven with the following:

```
    <dependency>
        <groupId>org.littleshoot</groupId>
        <artifactId>littleproxy</artifactId>
        <version>1.1.0-beta1-SNAPSHOT</version>
    </dependency>
```

Once you've included LittleProxy, you can start the server with the following:

```java
HttpProxyServer server =
    DefaultHttpProxyServer.bootstrap()
        .withPort(8080)
        .start();
```

To filter HTTP traffic, you can add request and response filters using a 
`HttpFiltersSource(Adapter)`, for example:

```java
HttpProxyServer server =
    DefaultHttpProxyServer.bootstrap()
        .withPort(8080)
        .withFiltersSource(new HttpFiltersSourceAdapter() {
            public HttpFilters filterRequest(HttpRequest originalRequest, ChannelHandlerContext ctx) {
               return new HttpFiltersAdapter(originalRequest) {
                  @Override
                  public HttpResponse clientToProxyRequest(HttpObject httpObject) {
                      // TODO: implement your filtering here
                      return null;
                  }

                  @Override
                  public HttpObject serverToProxyResponse(HttpObject httpObject) {
                      // TODO: implement your filtering here
                      return httpObject;
                  }
            }
        })
        .start();
```

Please have a look into this base base class to see the hooks you can use. 

To enable aggregator and inflater you have to return a value greater than 0 in 
your `HttpFiltersSource#get(Request/Response)BufferSizeInBytes()` methods. This 
provides to you a `FullHttp(Request/Response)' with the complete content in your 
filter uncompressed. Otherwise you have to handle the chunks yourself.

```java
    @Override
    public int getMaximumResponseBufferSizeInBytes() {
        return 10 * 1024 * 1024;
    }
```

This size limit applies to every connection. To disable aggregating by URL at 
*.iso or *dmg files for example, you can return in your filters source a filter 
like this:

```java
return new HttpFiltersAdapter(originalRequest, serverCtx) {
    @Override
    public void proxyToServerConnectionSucceeded(ChannelHandlerContext serverCtx) {
        ChannelPipeline pipeline = serverCtx.pipeline();
        if (pipeline.get("inflater") != null) {
            pipeline.remove("inflater");
        }
        if (pipeline.get("aggregator") != null) {
            pipeline.remove("aggregator");
        }
        super.proxyToServerConnectionSucceeded(serverCtx);
    }
};
```
This enables huge downloads in an application, which regular handles size 
limited `FullHttpResponse`s to modify its content, HTML for example. 

If you want to create additional proxy servers with similar configuration but
listening on different ports, you can clone an existing server.  The cloned
servers will share event loops to reduce resource usage and when one clone is
stopped, all are stopped.

```java
existingServer.clone().withPort(8081).start()
```

For examples of configuring logging, see [src/test/resources/log4j.xml](src/test/resources/log4j.xml).

If you have questions, please visit our Google Group here:

https://groups.google.com/forum/#!forum/littleproxy

To subscribe, send an E-Mail to mailto:LittleProxy+subscribe@googlegroups.com. 
Simply answering, don't clicking the button, bypasses Googles registration 
process. You will become a member. 

Project reports, including the [API Documentation]
(http://littleproxy.org/apidocs/index.html), can be found here:

http://littleproxy.org

Benchmarking instructions and results can be found [here](performance).

Acknowledgments
---------------

Many thanks to [The Measurement Factory](http://www.measurement-factory.com/) for the
use of [Co-Advisor](http://coad.measurement-factory.com/) for HTTP standards
compliance testing. 
