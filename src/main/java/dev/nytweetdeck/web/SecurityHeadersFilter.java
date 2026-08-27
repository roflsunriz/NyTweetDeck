package dev.nytweetdeck.web;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.util.Locale;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    private static final String LOCAL_DOMAIN = "ny.tweetdeck.com";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; img-src 'self' data: https:; media-src 'self' https:; "
                        + "style-src 'self'; script-src 'self'; connect-src 'self'; "
                        + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        if (request.getRequestURI().startsWith("/api/") && !isTrustedLocalRequest(request)) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean isTrustedLocalRequest(HttpServletRequest request) {
        if (!isTrustedHost(request.getServerName()) || !isLoopbackAddress(request.getRemoteAddr())) {
            return false;
        }
        var fetchSite = request.getHeader("Sec-Fetch-Site");
        if (fetchSite != null
                && !fetchSite.equalsIgnoreCase("same-origin")
                && !fetchSite.equalsIgnoreCase("none")) {
            return false;
        }
        var origin = request.getHeader("Origin");
        if (origin == null || origin.equals("null")) {
            return origin == null;
        }
        try {
            var originUri = URI.create(origin);
            var expectedPort = request.getServerPort();
            var originPort = originUri.getPort();
            if (originPort == -1) {
                originPort = "https".equalsIgnoreCase(originUri.getScheme()) ? 443 : 80;
            }
            var originHost = originUri.getHost();
            var trustedScheme = isLoopbackHost(originHost)
                    ? "http".equalsIgnoreCase(originUri.getScheme())
                            || "https".equalsIgnoreCase(originUri.getScheme())
                    : LOCAL_DOMAIN.equalsIgnoreCase(originHost)
                            && "https".equalsIgnoreCase(originUri.getScheme());
            return trustedScheme
                    && isTrustedHost(originHost)
                    && originPort == expectedPort;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isTrustedHost(String host) {
        return isLoopbackHost(host) || (host != null && LOCAL_DOMAIN.equalsIgnoreCase(host));
    }

    private static boolean isLoopbackAddress(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (RuntimeException | java.net.UnknownHostException exception) {
            return false;
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        var normalized = host.toLowerCase(Locale.ROOT);
        return normalized.equals("127.0.0.1")
                || normalized.equals("localhost")
                || normalized.equals("::1")
                || normalized.equals("[::1]");
    }
}
