import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;

import org.littleshoot.proxy.HttpFilters;
import org.littleshoot.proxy.HttpFiltersAdapter;
import org.littleshoot.proxy.HttpFiltersSource;
import org.littleshoot.proxy.HttpFiltersSourceAdapter;
import org.littleshoot.proxy.HttpProxyServer;
import org.littleshoot.proxy.impl.DefaultHttpProxyServer;

/**
 * Runs a LittleProxy that forwards HTTP requests to OSNews.
 */
public class App
{
    private static final int PROXY_PORT = 8080;

    public static void main(String[] args)
    {

        HttpFiltersSource filtersSource2 = new HttpFiltersSourceAdapter() {
            @Override
            public HttpFilters filterRequest(HttpRequest originalRequest) {
                return new HttpFiltersAdapter(originalRequest) {
                    @Override
                    public HttpResponse requestPre(HttpObject httpObject) {
                        if (httpObject instanceof HttpRequest) {
                            HttpRequest req = (HttpRequest) httpObject;
                            req.headers().set("Host", "www.osnews.com");
                        }
                        return null;
                    }
                };
            }
        };

        System.out.println("Starting proxy on port " + PROXY_PORT);
        DefaultHttpProxyServer.bootstrap()
                .withPort(PROXY_PORT)
                // .withManInTheMiddle(new SelfSignedMitmManager())
                .withFiltersSource(filtersSource2)
                .start();
    }
}