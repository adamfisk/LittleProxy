# Release Notes

- 2.0.0-beta-1
  - New Maven coordinates
  - Moved from Java 7 to 8
  - Updated dependency versions
  - **Breaking change:**  Made client details available to ChainedProxyManager
  - Refactored MITM manager to accept engine with user-defined parameters
  - Added ability to load keystore from classpath
  
- 2.0.0-beta-2
  - Added support for proxy protocol.  See https://www.haproxy.com/blog/haproxy/proxy-protocol/ and https://www.haproxy.org/download/1.8/doc/proxy-protocol.txt for protocol details.
  
- 2.0.0-beta-3
  - Upgraded Netty, guava, Hamcrest, Jetty, Selenium, Apache commons cli and lang3
  - Upgrade Maven plugins to the latest versions
  
- 2.0.0-beta-4
  - Allow users to set their own sesrvergroup within the bootstrap helper
  - Added support for chained SOCKS proxies

- 2.0.0-beta-5
  - Treat an upstream SOCKS proxy as if it is the origin server
  - Fixed memoryLeak in ClientToProxyConnection

- 2.0.0-beta-6
  - Cleaned up old code to conform with newer version of Netty
  - Deprecated UDT support because it's deprecated in Netty
  - Removed performance test code because it seems to be confusing GitHub into thinking that this is a PHP project.
  