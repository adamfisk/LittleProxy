package org.littleshoot.proxy.ntlm;

import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHENTICATE;
import static io.netty.handler.codec.http.HttpHeaders.Names.PROXY_AUTHORIZATION;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import java.io.IOException;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is responsible for writing and reading NTLM related request and
 * response headers respectively. It delegates the creating of NTLM messages to
 * a provider.
 */
public class NtlmHandlerImpl implements NtlmHandler {

	private static final Logger LOG = LoggerFactory.getLogger(NtlmHandlerImpl.class);

	private final NtlmProvider provider;

	private boolean type2Done;

	public NtlmHandlerImpl(NtlmProvider provider) {
		this.provider = provider;
	}

	@Override
	public void writeType1Header(HttpRequest httpRequest) {
		checkState(!type2Done, "NTLM Type2 shouldn't have been set yet!");
		byte[] type1 = provider.getType1();
		setAuthHeader(httpRequest, type1);
		LOG.debug("Set NTLM Type1 header");
	}

	@Override
	public void readType2Header(HttpResponse httpResponse) throws IOException {
		checkState(httpResponse.getStatus().equals(PROXY_AUTHENTICATION_REQUIRED), "No authentication challenge!");
		String proxyAuth = httpResponse.headers().get(PROXY_AUTHENTICATE);
		checkState(proxyAuth.startsWith("NTLM"), "Authentication challenge is %s!", proxyAuth);
		if (httpResponse.headers().contains("Proxy-Connection")) {
			checkState(!httpResponse.headers().get("Proxy-Connection").equalsIgnoreCase("close"), "Proxy-Connection closed!");
		}

		checkState(!type2Done, "NTLM Type2 shouldn't have been set yet!");

		String authChallenge = StringUtils.substringAfter(proxyAuth, "NTLM ");
		provider.setType2(Base64.decodeBase64(authChallenge));
		type2Done = true;
		LOG.debug("Got NTLM Type2 header");
	}

	@Override
	public void writeType3Header(HttpRequest httpRequest) {
		checkState(type2Done, "NTLM Type2 should have been set by now!");
		byte[] type3 = provider.getType3();
		setAuthHeader(httpRequest, type3);
		LOG.debug("Set NTLM Type3 header");
	}

	private static void setAuthHeader(HttpRequest httpRequest, byte[] msg) {
		httpRequest.headers().set(PROXY_AUTHORIZATION, "NTLM " + Base64.encodeBase64String(msg));
	}

}
