package io.terrakube.api.plugin.security.authentication.dex;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;

public class RunnerBasicAuthFilter extends OncePerRequestFilter {

    public static final String RUNNER_PATH_PREFIX = "/tfstate/v1/runner/";
    static final String RUNNER_USERNAME = "runner";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(RUNNER_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String bearer = toBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
        if (bearer == null) {
            chain.doFilter(request, response);
            return;
        }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override
            public String getHeader(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name) ? bearer : super.getHeader(name);
            }

            @Override
            public Enumeration<String> getHeaders(String name) {
                return HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)
                        ? Collections.enumeration(Collections.singletonList(bearer))
                        : super.getHeaders(name);
            }
        }, response);
    }

    static String toBearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return null;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int separator = decoded.indexOf(':');
        if (separator < 0 || !RUNNER_USERNAME.equals(decoded.substring(0, separator))) {
            return null;
        }
        String token = decoded.substring(separator + 1);
        return token.isBlank() ? null : "Bearer " + token;
    }
}
