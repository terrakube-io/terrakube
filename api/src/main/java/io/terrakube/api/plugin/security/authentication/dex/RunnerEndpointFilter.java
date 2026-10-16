package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.plugin.security.token.RunnerToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

public class RunnerEndpointFilter extends OncePerRequestFilter {

    record Rule(Set<String> methods, String pattern) {
    }

    static final List<Rule> ALLOWED = List.of(
            new Rule(Set.of("GET", "POST", "PUT", "DELETE"), "/tfstate/v1/runner/**"),
            new Rule(Set.of("GET"), "/tfstate/v1/organization/*/workspace/*/state/**"),
            new Rule(Set.of("GET"), "/tfstate/v1/organization/*/workspace/*/jobId/*/step/*/terraform.tfstate"),
            new Rule(Set.of("GET", "POST"), "/context/v1/*"),
            new Rule(Set.of("POST"), "/logs"),
            new Rule(Set.of("POST"), "/logs/*/setup-consumer-groups"),
            new Rule(Set.of("GET"), "/api/v1/organization/*/workspace/*"),
            new Rule(Set.of("GET", "PATCH"), "/api/v1/organization/*/job/*"),
            new Rule(Set.of("PATCH"), "/api/v1/organization/*/job/*/step/*"),
            new Rule(Set.of("GET"), "/remote/tfe/v2/ping"),
            new Rule(Set.of("GET"), "/remote/tfe/v2/organizations/*/entitlement-set"),
            new Rule(Set.of("GET"), "/remote/tfe/v2/organizations/*/workspaces/*"),
            new Rule(Set.of("GET"), "/remote/tfe/v2/workspaces/*/current-state-version"),
            new Rule(Set.of("GET"), "/remote/tfe/v2/workspaces/*/current-state-version-outputs"));

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwt && RunnerToken.isRunner(jwt.getTokenAttributes())
                && !allowed(request.getMethod(), request.getRequestURI())) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean allowed(String method, String path) {
        return ALLOWED.stream().anyMatch(rule -> rule.methods().contains(method) && MATCHER.match(rule.pattern(), path));
    }
}
