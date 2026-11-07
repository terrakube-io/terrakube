package io.terrakube.registry.routing;

import java.io.IOException;
import java.util.Locale;

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Serves the registry's legacy paths under the canonical {@code /api/private-registry} prefix used on
 * a single-domain host: {@code /api/private-registry/<legacy>} is forwarded to {@code /<legacy>}.
 *
 * <p>The filter runs before Spring Security, so the security chain only evaluates the legacy path on
 * the FORWARD dispatch. Because it also runs before Spring Security's request firewall, it rejects
 * suspicious raw paths itself.
 *
 * <p>Registered only in single-domain mode (TerrakubePublicUrl set), so split-domain installs do
 * not grow a second spelling of every path that ingress or WAF rules would not match.
 *
 * <p>A near-identical copy lives in api/src/main/java/io/terrakube/api/plugin/routing/CanonicalPathForwardFilter.java; keep the two in step.
 */
public class CanonicalPathForwardFilter extends OncePerRequestFilter {

    static final String PREFIX = "/api/private-registry";
    private static final String[] REJECTED = {";", "%2e", "%2f", "%5c", "\\", "/..", "//"};

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String raw = request.getRequestURI().substring(request.getContextPath().length());
        if (!raw.startsWith(PREFIX + "/")) {
            chain.doFilter(request, response);
            return;
        }
        if (isSuspicious(raw)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String path = servletPath(request);
        if (path.equals(PREFIX + "/actuator") || path.startsWith(PREFIX + "/actuator/")) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        request.getRequestDispatcher(raw.substring(PREFIX.length())).forward(request, response);
    }

    static boolean isSuspicious(String rawPath) {
        String lower = rawPath.toLowerCase(Locale.ROOT);
        for (String token : REJECTED) {
            if (lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String servletPath(HttpServletRequest request) {
        String pathInfo = request.getPathInfo();
        return pathInfo == null ? request.getServletPath() : request.getServletPath() + pathInfo;
    }

    @Configuration
    @ConditionalOnExpression("!'${io.terrakube.public-url:}'.isBlank()")
    static class Registration {
        @Bean
        FilterRegistrationBean<CanonicalPathForwardFilter> canonicalPathForwardFilter() {
            FilterRegistrationBean<CanonicalPathForwardFilter> bean =
                    new FilterRegistrationBean<>(new CanonicalPathForwardFilter());
            bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
            bean.setDispatcherTypes(DispatcherType.REQUEST);
            // No URL pattern: the container matches patterns on the normalized path, which would let
            // raw paths such as /api/..;/actuator bypass the checks above.
            return bean;
        }
    }
}
