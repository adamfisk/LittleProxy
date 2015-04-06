package org.littleshoot.proxy.ntlm;

import org.littleshoot.proxy.ChainedProxy;

import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

/**
 * This serves as an extension to {@link ChainedProxy} which requires NTLM
 * authentication. Implementation should set Type1 and Type3 Proxy-Authorization
 * headers to the request.
 */
public interface NtlmHandler {

	void writeType1Header(HttpRequest httpRequest);

	void readType2Header(HttpResponse httpResponse) throws Exception;

	void writeType3Header(HttpRequest httpRequest);

}
