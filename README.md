[![Build Status](https://travis-ci.org/adamfisk/LittleProxy.png?branch=master)](https://travis-ci.org/adamfisk/LittleProxy)

LittleProxy is a high performance HTTP proxy written in Java atop Trustin Lee's excellent [Netty](netty.io) event-based networking library. It's quite stable, performs well, and is easy to integrate into your projects. 

One option is to clone LittleProxy and run it from the command line. This is as simple as:

```
$ git clone git://github.com/adamfisk/LittleProxy.git
$ cd LittleProxy
$ ./run.bash
```

You can embed LittleProxy in your own projects through maven with the following:

```
    <dependency>
        <groupId>org.littleshoot</groupId>
        <artifactId>littleproxy</artifactId>
        <version>1.0.0-beta7</version>
    </dependency>
```

Once you've included LittleProxy, you can start the server with the following:

```java
HttpProxyServer server =
    DefaultHttpProxyServer.bootstrap()
        .withPort(8080)
        .start();
```

There are lots of filters and such you can also add to LittleProxy. You can add
request and response filters using an `HttpFiltersSource(Adapter)`, for example:

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
                  public HttpResponse proxyToServerRequest(HttpObject httpObject) {
                      // TODO: implement your filtering here
                      return null;
                  }

                  @Override
                  public HttpObject serverToProxyResponse(HttpObject httpObject) {
                      // TODO: implement your filtering here
                      return httpObject;
                  }

                  @Override
                  public HttpObject proxyToClientResponse(HttpObject httpObject) {
                      // TODO: implement your filtering here
                      return httpObject;
                  }   
               };
            }
        })
        .start();
```                

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

Project reports, including the [API Documentation]
(http://littleproxy.org/apidocs/index.html), can be found here:

http://littleproxy.org

Benchmarking instructions and results can be found [here](performance).

Acknowledgments
---------------

Many thanks to [The Measurement Factory](http://www.measurement-factory.com/) for the
use of [Co-Advisor](http://coad.measurement-factory.com/) for HTTP standards
compliance testing. 

Release History
---------------

### 1.0.0-beta4 - Bug fixes

- [#113 Handle exceptions in filter implementations](https://github.com/adamfisk/LittleProxy/issues/113)
- [#117 withPort() after clone() doesn't work](https://github.com/adamfisk/LittleProxy/issues/117)


### 1.0.0-beta3 - Bug fixes

- [#96 If idleConnectionTimeout is exceeded no response is returned](https://github.com/adamfisk/LittleProxy/issues/96)
- [#98 Turn down log level on errors/warnings about which nothing can be done](https://github.com/adamfisk/LittleProxy/issues/98)
- [#106 UDT binding reverses local and remote addresses](https://github.com/adamfisk/LittleProxy/issues/106)


### 1.0.0-beta2 - Basic Man in the Middle Support

This release added back basic support for Man in the Middle (MITM) proxying.
The current MITM support is intended primarily for projects that wish to use
LittleProxy to facilitate HTTP related testing.  See
[MitmProxyTest](src/test/java/org/littleshoot/proxy/MitmProxyTest.java) for an
example of how to use the updated MITM support.
 
[Certificate impersonation](https://github.com/adamfisk/LittleProxy/issues/85)
would need to be implemented in order for LittleProxy to work well in an
end-user facing capacity.  This release includes the hooks for doing so, through
the new [MitmManager](src/main/java/org/littleshoot/proxy/MitmManager.java)
abstraction.

#### Fixed Issues

- [#79 Add back Man in the Middle Support](https://github.com/adamfisk/LittleProxy/issues/79)
- [#88 Issue with HTTP 301/302 with MITM](https://github.com/adamfisk/LittleProxy/issues/88)
- [#90 HTTPS requests without host:port get assigned to a different connection than the one opened with the initial CONNECT](https://github.com/adamfisk/LittleProxy/issues/90)
- [#91 Allow chained proxies to not do client authentication if they don't want to](https://github.com/adamfisk/LittleProxy/issues/91)
- [#92 MITM proxying to hosts whose names begin with "http" is broken](https://github.com/adamfisk/LittleProxy/issues/92)
- [#93 Filters on reused ProxyToServerConnections still reference the first HttpRequest that opened the connection](https://github.com/adamfisk/LittleProxy/issues/93)


### 1.0.0-beta1 - Netty 4 Upgrade

This release switched LittleProxy from Netty 3 to Netty 4.  As part of the
upgrade, LittleProxy's public API and internal implementation were significantly
refactored for maintainability and API stability.

Support for Man in the Middle proxying was temporarily dropped in this release.
