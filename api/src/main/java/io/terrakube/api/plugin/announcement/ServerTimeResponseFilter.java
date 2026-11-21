package io.terrakube.api.plugin.announcement;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

@Component
public class ServerTimeResponseFilter extends OncePerRequestFilter {

    public static final String SERVER_TIME_HEADER = "X-Server-Time";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri != null
                && (uri.contains("/api/v1/announcement")
                        || uri.contains("/graphql/api/v1")
                        || uri.contains("/public/v1/announcements"))) {
            response.setHeader(SERVER_TIME_HEADER, Instant.now().toString());
        }
        filterChain.doFilter(request, response);
    }
}
