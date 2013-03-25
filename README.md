[![Build Status](https://travis-ci.org/adamfisk/LittleProxy.png?branch=master)](https://travis-ci.org/adamfisk/LittleProxy)

LittleProxy is a high performance HTTP proxy written in Java atop Trustin Lee's excellent Netty event-based networking library. It's quite stable, performs well, and is easy to integrate into your projects. From Maven this as simple as:

```
    <dependency>
        <groupId>org.littleshoot</groupId>
        <artifactId>littleproxy</artifactId>
        <version>0.4</version>
    </dependency>
```

Alternatively, you can also work off of SNAPSHOT releases, which should generally be quite stable, as in:

```
    <dependency>
        <groupId>org.littleshoot</groupId>
        <artifactId>littleproxy</artifactId>
        <version>0.5-SNAPSHOT</version>
    </dependency>
```

If you do use a SNAPSHOT, you need to add the Sonatype snapshot repository to your pom.xml, as in:

```
    <repository>
        <id>sonatype-nexus-snapshots</id>
        <name>Sonatype Nexus Snapshots</name>
        <url>https://oss.sonatype.org/content/repositories/snapshots</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>

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
