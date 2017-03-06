package org.littleshoot.proxy;

import com.google.common.io.BaseEncoding;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.Charset;

/**
 *
 */
public class BasicProxyAuthenticator {

  private final ProxyAuthenticator proxyAuthenticator;

  public BasicProxyAuthenticator(ProxyAuthenticator proxyAuthenticator) {
    this.proxyAuthenticator = proxyAuthenticator;
  }

  public boolean authenticate(String proxyAuthorizationHeaderValue) {
    String value = StringUtils.substringAfter(proxyAuthorizationHeaderValue, "Basic ").trim();

    byte[] decodedValue = BaseEncoding.base64().decode(value);

    String decodedString = new String(decodedValue, Charset.forName("UTF-8"));

    String userName = StringUtils.substringBefore(decodedString, ":");
    String password = StringUtils.substringAfter(decodedString, ":");

    return proxyAuthenticator.authenticate(userName, password);
  }

}
