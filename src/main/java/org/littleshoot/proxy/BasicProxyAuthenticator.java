package org.littleshoot.proxy;

import com.google.common.io.BaseEncoding;

import org.apache.commons.lang3.StringUtils;

import java.nio.charset.Charset;

/**
 *
 */
public abstract class BasicProxyAuthenticator implements ProxyAuthenticator {

  @Override
  public boolean authenticate(String proxyAuthorizationHeaderValue) {
    String value = StringUtils.substringAfter(proxyAuthorizationHeaderValue, "Basic ").trim();

    byte[] decodedValue = BaseEncoding.base64().decode(value);

    String decodedString = new String(decodedValue, Charset.forName("UTF-8"));

    String userName = StringUtils.substringBefore(decodedString, ":");
    String password = StringUtils.substringAfter(decodedString, ":");

    return authenticate(userName, password);
  }

  /**
   * Authenticates the user using the specified userName and password.
   *
   * @param username
   *            The user name.
   * @param password
   *            The password.
   * @return <code>true</code> if the credentials are acceptable, otherwise
   *         <code>false</code>.
   * requests.
   */
  abstract boolean authenticate(String username, String password);

  abstract public String getRealm();
}
