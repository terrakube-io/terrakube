package io.terrakube.api.plugin.security.authentication.dex;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class RunnerBasicAuthFilterTest {

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void convertsOnlyTheRunnerUser() {
        assertEquals("Bearer a.b.c", RunnerBasicAuthFilter.toBearer(basic("runner:a.b.c")));
        assertNull(RunnerBasicAuthFilter.toBearer(basic("admin:a.b.c")));
        assertNull(RunnerBasicAuthFilter.toBearer(basic("runner:")));
        assertNull(RunnerBasicAuthFilter.toBearer("Basic %%%"));
        assertNull(RunnerBasicAuthFilter.toBearer("Bearer a.b.c"));
        assertNull(RunnerBasicAuthFilter.toBearer(null));
    }

    @Test
    void rewritesTheHeaderOnRunnerPathsOnly() throws Exception {
        RunnerBasicAuthFilter filter = new RunnerBasicAuthFilter();

        MockHttpServletRequest runner = new MockHttpServletRequest("POST", "/tfstate/v1/runner/state");
        runner.addHeader("Authorization", basic("runner:a.b.c"));
        MockFilterChain runnerChain = new MockFilterChain();
        filter.doFilter(runner, new MockHttpServletResponse(), runnerChain);
        assertEquals("Bearer a.b.c", ((jakarta.servlet.http.HttpServletRequest) runnerChain.getRequest()).getHeader("Authorization"));

        MockHttpServletRequest other = new MockHttpServletRequest("GET", "/api/v1/organization");
        other.addHeader("Authorization", basic("runner:a.b.c"));
        MockFilterChain otherChain = new MockFilterChain();
        filter.doFilter(other, new MockHttpServletResponse(), otherChain);
        assertEquals(basic("runner:a.b.c"), ((jakarta.servlet.http.HttpServletRequest) otherChain.getRequest()).getHeader("Authorization"));
    }
}
