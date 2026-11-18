package io.terrakube.registry.configuration;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

@Configuration
public class HttpCacheConfig {

    /**
     * ETag and 304 for the registry protocol metadata. It runs after the Spring Security filter chain
     * (order -100), so a request is authenticated and authorized before any 304 can be answered.
     * Responses marked no-store (download tickets, zips) get no ETag. The module.zip handler also turns off
     * the filter's body buffering, so archives are streamed instead of held in memory.
     */
    @Bean
    public FilterRegistrationBean<ShallowEtagHeaderFilter> shallowEtagHeaderFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.addUrlPatterns("/.well-known/*", "/terraform/modules/v1/*", "/terraform/providers/v1/*", "/terraform/readme/v1/*");
        registration.setOrder(Ordered.LOWEST_PRECEDENCE - 10);
        return registration;
    }
}
