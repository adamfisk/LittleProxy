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
