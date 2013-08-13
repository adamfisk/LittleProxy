[![Build Status](https://travis-ci.org/adamfisk/LittleProxy.png?branch=master)](https://travis-ci.org/adamfisk/LittleProxy)

LittleProxy is a high performance HTTP proxy written in Java atop Trustin Lee's excellent Netty event-based networking library. It's quite stable, performs well, and is easy to integrate into your projects. 

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
        <version>0.5.3</version>
    </dependency>
```

Once you've included LittleProxy, you can start the server with the following:

```
final HttpProxyServer server = new DefaultHttpProxyServer(8080);
server.start();
```

There are lots of filters and such you can also add to LittleProxy. You can add request and reponse filters, for example, as in:

```
final HttpProxyServer server = 
    new DefaultHttpProxyServer(PROXY_PORT, new HttpRequestFilter() {
        @Override
        public void filter(HttpRequest httpRequest) {
            System.out.println("Request went through proxy");
        }
    },
    new HttpResponseFilters() {
        @Override
        public HttpFilter getFilter(String hostAndPort) {
            return null;
        }
    });

server.start();
```

If you have questions, please visit our Google Group here:

https://groups.google.com/forum/#!forum/littleproxy

The main LittleProxy page is here:

http://www.littleshoot.org/littleproxy/

Acknowledgements
----------------

Many thanks to [The Measurement Factory](http://www.measurement-factory.com/) for the
use of [Co-Advisor](http://coad.measurement-factory.com/) for HTTP standards
compliance testing. 
