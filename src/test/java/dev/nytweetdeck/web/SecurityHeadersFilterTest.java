package dev.nytweetdeck.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    private final SecurityHeadersFilter filter = new SecurityHeadersFilter();

    @Test
    void rejectsCrossSiteAndDnsRebindingApiRequests() throws Exception {
        var crossSite = request("127.0.0.1", 18080);
        crossSite.addHeader("Origin", "https://attacker.example");
        crossSite.addHeader("Sec-Fetch-Site", "cross-site");
        var crossSiteResponse = new MockHttpServletResponse();

        filter.doFilter(crossSite, crossSiteResponse, new MockFilterChain());

        assertThat(crossSiteResponse.getStatus()).isEqualTo(403);

        var rebound = request("attacker.example", 18080);
        var reboundResponse = new MockHttpServletResponse();
        filter.doFilter(rebound, reboundResponse, new MockFilterChain());
        assertThat(reboundResponse.getStatus()).isEqualTo(403);
    }

    @Test
    void allowsSameOriginBrowserAndOriginlessLocalRequests() throws Exception {
        var browser = request("127.0.0.1", 18080);
        browser.addHeader("Origin", "http://127.0.0.1:18080");
        browser.addHeader("Sec-Fetch-Site", "same-origin");
        var browserResponse = new MockHttpServletResponse();
        var browserChain = new MockFilterChain();

        filter.doFilter(browser, browserResponse, browserChain);

        assertThat(browserResponse.getStatus()).isEqualTo(200);
        assertThat(browserChain.getRequest()).isNotNull();
        assertThat(browserResponse.getHeader("Cross-Origin-Resource-Policy"))
                .isEqualTo("same-origin");

        var cli = request("localhost", 18080);
        var cliChain = new MockFilterChain();
        filter.doFilter(cli, new MockHttpServletResponse(), cliChain);
        assertThat(cliChain.getRequest()).isNotNull();
    }

    private static MockHttpServletRequest request(String host, int port) {
        var request = new MockHttpServletRequest("POST", "/api/v1/posts/1/actions/like");
        request.setServerName(host);
        request.setServerPort(port);
        return request;
    }
}
