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
HttpProxyServer server =
    DefaultHttpProxyServer.bootstrap()
        .withPort(8080)
        .start();
```

There are lots of filters and such you can also add to LittleProxy. You can add
request and response filters using an `HttpFiltersSource(Adapter)`, for example:

```
HttpProxyServer server =
    DefaultHttpProxyServer.bootstrap()
        .withPort(8080)
        .withFiltersSource(new HttpFiltersSourceAdapter() {
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                // Check the originalRequest to see if we want to filter it
                boolean wantToFilterRequest = ...;
                
                if (!wantToFilterRequest) {
                    return null;
                } else {
                    return new HttpFiltersAdapter(originalRequest) {
                        @Override
                        public HttpResponse requestPre(HttpObject httpObject) {
                            // TODO: implement your filtering here
                            return null;
                        }
                    
                        @Override
                        public HttpResponse requestPost(HttpObject httpObject) {
                            // TODO: implement your filtering here
                            return null;
                        }
                    
                        @Override
                        public void responsePre(HttpObject httpObject) {
                            // TODO: implement your filtering here
                        }
                    
                        @Override
                        public void responsePost(HttpObject httpObject) {
                            // TODO: implement your filtering here
                        }   
                    };
                }
            }
        });
        .start();
```                

If you want to create additional proxy servers with similar configuration but
listening on different ports, you can clone an existing server.  The cloned
servers will share event loops to reduce resource usage and when one clone is
stopped, all are stopped.

```
existingServer.clone().withPort(8081).start()
```

If you have questions, please visit our Google Group here:

https://groups.google.com/forum/#!forum/littleproxy

Project reports, including the [API Documentation]
(http://adamfisk.github.io/LittleProxy/apidocs/index.html), can be found here:

http://adamfisk.github.io/LittleProxy/

Benchmarking instructions and results can be found [here](performance).

Acknowledgments
---------------

Many thanks to [The Measurement Factory](http://www.measurement-factory.com/) for the
use of [Co-Advisor](http://coad.measurement-factory.com/) for HTTP standards
compliance testing. 
