package org.littleshoot.proxy;

import io.netty.handler.codec.http.HttpRequest;

import java.net.InetSocketAddress;
import java.util.Queue;

import org.junit.Ignore;
import org.junit.Test;
import org.littleshoot.proxy.ntlm.JcifsNtlmProvider;
import org.littleshoot.proxy.ntlm.NtlmHandler;
import org.littleshoot.proxy.ntlm.NtlmHandlerImpl;

/**
 * Test NTLM authentication to chained proxy. Since LittleProxy cannot verify
 * NTLM credentials, chained proxy supporting NTLM authentication (e.g.
 * FreeProxy) needs to be run explicitly.
 */
public class NtlmChainedProxyTest extends BaseProxyTest {

	private static final InetSocketAddress ADDRESS = new InetSocketAddress("127.0.0.1", 8080);
	private static final String USERNAME = System.getProperty("user.name");
	private static final String PASSWORD = "xxxxx";
	private static final String DOMAIN = "";

	private class NtlmChainedProxy extends ChainedProxyAdapter {

		private final NtlmHandler handler = new NtlmHandlerImpl(new JcifsNtlmProvider(USERNAME, PASSWORD, DOMAIN));

		@Override
		public InetSocketAddress getChainedProxyAddress() {
			return ADDRESS;
		}

		@Override
		public NtlmHandler getNtlmHandler() {
			return handler;
		}
	}

	@Override
	protected void setUp() throws Exception {
		proxyServer = bootstrapProxy().withName("Downstream").withPort(proxyServerPort).withChainProxyManager(new ChainedProxyManager() {
			@Override
			public void lookupChainedProxies(HttpRequest httpRequest, Queue<ChainedProxy> chainedProxies) {
				chainedProxies.add(newChainedProxy());
			}
		}).start();

	}

	protected ChainedProxy newChainedProxy() {
		return new NtlmChainedProxy();
	}

	@Override
	protected boolean isChained() {
		return true;
	}

	@Override
	protected boolean isChainedNTLM() {
		return true;
	}

	@Ignore
	@Test
	public void testProxyWithBadAddress() {
	}

	@Ignore
	@Test
	public void testSimplePostRequest() {
	}

	@Ignore
	@Test
	public void testHeadRequestFollowedByGet() {
	}

}
