package io.terrakube.registry.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.CacheControl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class HttpCacheConfigTest {

    /** Order of the Spring Security filter chain registration (SecurityProperties.DEFAULT_FILTER_ORDER). */
    private static final int SECURITY_FILTER_ORDER = -100;

    @RestController
    static class Metadata {
        @GetMapping("/metadata")
        ResponseEntity<String> metadata() {
            return ResponseEntity.ok().cacheControl(CacheControl.noCache().cachePrivate()).body("{}");
        }

        @GetMapping("/ticket")
        ResponseEntity<String> ticket() {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body("zip");
        }
    }

    /** A 304 must only be possible once the request passed authentication, so the filter runs after Spring Security. */
    @Test
    void etagFilterRunsAfterTheSecurityFilterChain() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new HttpCacheConfig().shallowEtagHeaderFilter();

        assertThat(registration.getOrder()).isGreaterThan(SECURITY_FILTER_ORDER);
        assertThat(registration.getUrlPatterns()).containsExactlyInAnyOrder(
                "/.well-known/*", "/terraform/modules/v1/*", "/terraform/providers/v1/*", "/terraform/readme/v1/*");
    }

    @Test
    void conditionalRequestsGet304AndNoStoreResponsesGetNoEtag() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Metadata()).addFilters(new ShallowEtagHeaderFilter()).build();

        String etag = mockMvc.perform(get("/metadata")).andExpect(status().isOk())
                .andExpect(header().exists("ETag")).andReturn().getResponse().getHeader("ETag");
        mockMvc.perform(get("/metadata").header("If-None-Match", etag)).andExpect(status().isNotModified());
        mockMvc.perform(get("/ticket")).andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"));
    }
}
