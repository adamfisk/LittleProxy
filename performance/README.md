# Benchmarking LittleProxy

To understand the overhead and performance characteristics of LittleProxy
compared with other proxies (and no proxy), we've developed a simple benchmark.

The goal of the benchmark is to understand LittleProxy's performance without
network variability being a factor.

To that end, the test serves a local mirror of Wikipedia's [Germany]
(http://en.wikipedia.org/wiki/Germany) page from a Jetty web server.

We use [JMeter](http://jmeter.apache.org/) to generate load from the same
machine as the Jetty server, thereby cutting out the network as a factor.

The JMeter test uses 4 concurrent threads to generate requests to [germany.html]
(site/wikipedia/germany.html).  Each thread makes 200 consecutive requests with
no delay in between.  To simulate a normal browser, JMeter looks for resources
referenced in the page and loads those with each page as well.

TODO - try adding in a delay and see if that makes Apache and node-http-proxy
happier. 

## Methodology

Tests should always be run starting with a cold back-end, proxy and JMeter.

1. Run `./perfServer.bash` to start a test web server on port 9000 (Jetty)
2. Start your proxy on port 8080 (for LittleProxy, `./run.bash`)
3. Open and run the [JMeter Germany Page Test]
   (jmeter/Local Wikipedia Germany Performance Test.jmx)
4. To run without a proxy, edit "The Users" and remove the proxy settings at
   the bottom

## Compared Proxies

### [node-http-proxy 0.10.3](https://github.com/nodejitsu/node-http-proxy)

Run using [node-proxy.js](other_proxies/node-proxy.js).

Note - this is not a generic proxy, it is actually configured to proxy
everything to the web server listening on port 9000.

### Squid using [SquidMan](http://squidman.net/squidman/)

Note - Squid is a caching proxy, but for these tests the cache was disabled.

### Apache 2 [mod_proxy](http://httpd.apache.org/docs/2.2/mod/mod_proxy.html)

```
Server version: Apache/2.2.22 (Unix)
Server built:   Dec  9 2012 18:57:18
```

Configured with [httpd.conf](other_proxies/httpd.conf).

### [nginx 1.4.2](http://nginx.org/)

Installed per [these instructions]
(http://learnaholic.me/2012/10/10/installing-nginx-in-mac-os-x-mountain-lion/).

Configured with [nginx.conf](other_proxies/nginx.conf).

Note - we are using a single worker process

## Results

This section lists historical results from running the benchmark.

### About the Tables

The columns "Avg", "Min", "Max" and "Std Dev" refer to response time in ms.

The column "Error %" indicates the percent of pages that had some error.  Even
if only one image failed to load, the entire page is considered to be in error.
 
### August 29, 2013

#### Environment

```
Machine:          MacBook Air 2013
OS:               OS X 10.8.4
Processor:        1.7 GHz Intel Core i7
Memory:           8 GB 1600 MHz DDR3
Network Adapter:  Virtual Loopback Interface
```

#### Measurements

| Proxy            | Avg  | Min |  Max  | Std Dev | Error % | Pages/s | MB/s |
|------------------|-----:|----:|------:|--------:|--------:|--------:|-----:|
| No Proxy         |  181 |  96 |   547 |     66  |    0    |  13.9   | 35.8 |
| LittleProxy      |  263 | 113 |   778 |     88  |    0    |  11.0   | 28.6 |
| Squid            |  299 | 121 |   702 |    104  |    0    |   9.9   | 25.8 |
| node-http-proxy* |   70 |  11 |   808 |    126  |   88    |  29.7*  |  9.5 |
| Apache 2*        |  542 |   7 | 20120 |   1928  |   42    |   4.7   |  7.4 |
| nginx            | 1246 | 129 | 18206 |   3155  |    4    |   2.8   |  7.2 |

\* - These tests had very high error rates due to network-related issues, so
take their numbers with a big grain of salt.

Apache 2 ran fine for several iterations and then started reporting lots of
errors like this:

```
[Thu Aug 29 09:35:17 2013] [error] (49)Can't assign requested address: proxy: HTTP: attempt to connect to 127.0.0.1:9000 (*) failed
```

node-http-proxy similarly ran fine for a while and then started reporting this:

```
An error has occurred: {"code":"EADDRNOTAVAIL","errno":"EADDRNOTAVAIL","syscall":"connect"}
```

I tried changing both to bind on 127.0.0.1 instead of localhost, but that didn't
help.  I'm not sure if they're leaking file descriptors or what's going on.