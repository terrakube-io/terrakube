package io.terrakube.registry.configuration.authentication.dex;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.terrakube.client.TerrakubeClient;
import io.terrakube.registry.configuration.OpenRegistryProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "io.terrakube.registry.authentication", name = "type", havingValue = "DEX")
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
public class DexWebSecurityAdapter {


    @Bean
    @Order(0)
    public SecurityFilterChain filterChainTerraformLogin(HttpSecurity http) throws Exception {
        return http.securityMatchers(
                        requestMatcherConfigurer ->
                                requestMatcherConfigurer
                                        .requestMatchers(HttpMethod.GET, "/.well-known/**")
                ).authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain filterChain(HttpSecurity http, @Value("${io.terrakube.token.issuer-uri}") String issuerUri, @Value("${io.terrakube.token.pat}") String patJwtSecret, @Value("${io.terrakube.token.internal}") String internalJwtSecret, TerrakubeClient terrakubeClient, OpenRegistryProperties openRegistryProperties) throws Exception {
        http.cors(Customizer.withDefaults())
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers("/.well-known/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/terraform/modules/v1/download/**").permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> {
                    AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver = RegistryAuthenticationManagerResolver
                            .builder()
                            .issuerUri(issuerUri)
                            .patSecret(patJwtSecret)
                            .internalSecret(internalJwtSecret)
                            .terrakubeClient(terrakubeClient)
                            .federatedCacheExpireAfterWrite(openRegistryProperties.getFederatedCacheExpireAfterWrite())
                            .federatedCacheMaximumSize(openRegistryProperties.getFederatedCacheMaximumSize())
                            .providerManagerCacheExpireAfterWrite(openRegistryProperties.getProviderManagerCacheExpireAfterWrite())
                            .providerManagerCacheMaximumSize(openRegistryProperties.getProviderManagerCacheMaximumSize())
                            .build();
                    oauth2.authenticationManagerResolver(authenticationManagerResolver);
                });

        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${io.terrakube.ui.fqdn:http://localhost:3000}") String uiDomain) {
        log.info("CORS for UI Domain {}", uiDomain);
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(uiDomain.split(",")));
        configuration.setAllowedHeaders(Arrays.asList("Access-Control-Allow-Headers", "Access-Control-Allow-Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers", "Origin", "Cache-Control", "Content-Type", "Authorization"));
        configuration.setAllowedMethods(Arrays.asList("DELETE", "GET", "POST", "PATCH", "PUT", "OPTIONS"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
