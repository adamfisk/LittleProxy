package org.littleshoot.proxy;

import com.google.common.net.HostAndPort;
import io.netty.handler.codec.http.HttpMethod;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.HttpHost;

import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 * Tests just a single basic proxy running as a man in the middle, where the
 *   MTM manager is a selective manager, that for the purpose of the tests, always
 *   returns false.
 */
public class SelectiveMitmProxyTest extends BaseMitmProxyTest {
    private List<HostAndPort> mitmRequests = new ArrayList<>();

    @Override
    protected boolean isMITM() {
        return false;
    }

    @Override
    protected MitmManager getMitmManager() {
        return new SelectiveMitmManagerAdapter(super.getMitmManager()) {
            @Override
            public boolean shouldMITMPeer(String peerHost, int peerPort) {
                mitmRequests.add(HostAndPort.fromParts(peerHost, peerPort));
                return false;
            }
        };
    }

    @Override
    public void testSimpleGetRequest() throws Exception {
        super.testSimpleGetRequest();
        assertMethodSeenInRequestFilters(HttpMethod.GET);
        assertMethodSeenInResponseFilters(HttpMethod.GET);
        assertNoMitmChecks();
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimpleGetRequestOverHTTPS() throws Exception {
        super.testSimpleGetRequestOverHTTPS();
        assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
        assertSingleMitmCheck(httpsWebHost);
        assertMethodNotSeenInRequestFilters(HttpMethod.GET);
        assertMethodNotSeenInRequestFilters(HttpMethod.GET);
    }

    @Override
    public void testSimplePostRequest() throws Exception {
        super.testSimplePostRequest();
        assertMethodSeenInRequestFilters(HttpMethod.POST);
        assertMethodSeenInResponseFilters(HttpMethod.POST);
        assertNoMitmChecks();
        assertResponseFromFiltersMatchesActualResponse();
    }

    @Override
    public void testSimplePostRequestOverHTTPS() throws Exception {
        super.testSimplePostRequestOverHTTPS();
        assertMethodSeenInRequestFilters(HttpMethod.CONNECT);
        assertSingleMitmCheck(httpsWebHost);
        assertMethodNotSeenInRequestFilters(HttpMethod.POST);
        assertMethodNotSeenInRequestFilters(HttpMethod.POST);
    }

    private void assertNoMitmChecks() {
        assertThat(mitmRequests, is(empty()));
    }

    private void assertSingleMitmCheck(HttpHost host) {
        assertThat(mitmRequests, hasSize(1));
        assertThat(mitmRequests.get(0), is(equalTo(HostAndPort.fromParts(host.getHostName(), host.getPort()))));
    }
}
