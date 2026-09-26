package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.plugin.token.login.TerraformLoginProperties;
import io.terrakube.api.repository.FederatedRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
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
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
public class DexWebSecurityAdapter {

        @Bean
        @Order(0)
        public SecurityFilterChain filterChainTerraformLogin(HttpSecurity http) throws Exception {
                return http.securityMatchers(
                                requestMatcherConfigurer ->
                                        requestMatcherConfigurer
                                                .requestMatchers(HttpMethod.GET, "/.well-known/terraform.json")
                        ).authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                        .build();
        }

        @Bean
        @Order(1)
        public SecurityFilterChain filterChainOauthBroker(HttpSecurity http,
                                                          TerraformLoginProperties loginProperties) throws Exception {
                // No .cors() here on purpose: the broker is a server-rendered browser flow
                // (top-level redirects + a same-origin form POST), not an XHR API. A CorsFilter
                // would reject the consent POST's Origin header before the controller's own
                // Origin/Referer check runs.
                http.securityMatcher("/oauth/**")
                                .csrf(csrf -> csrf.disable());
                if (loginProperties.isEnabled()) {
                        // The broker endpoints authenticate themselves via the signed session
                        // cookie / PKCE / auth code; no Spring Security auth is applied here.
                        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
                } else {
                        // Feature off: the whole /oauth/** tree is invisible (404), not 401/403.
                        http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                                .exceptionHandling(ex -> {
                                        ex.authenticationEntryPoint((req, res, e) -> res.setStatus(404));
                                        ex.accessDeniedHandler((req, res, e) -> res.setStatus(404));
                                });
                }
                return http.build();
        }

        @Bean
        @Order(2)
        public SecurityFilterChain filterChain(HttpSecurity http,
                                               @Value("${io.terrakube.token.issuer-uri}") String issuerUri,
                                               @Value("${io.terrakube.token.pat}") String patJwtSecret,
                                               @Value("${io.terrakube.token.internal}") String internalJwtSecret, PatRepository patRepository,
                                               TeamTokenRepository teamTokenRepository, FederatedRepository federatedRepository) throws Exception {
                http.cors(Customizer.withDefaults())
                                .csrf(crsf -> crsf.ignoringRequestMatchers("/remote/tfe/v2/configuration-versions/*",
                                                "/tfstate/v1/archive/*/terraform.tfstate",
                                                "/tfstate/v1/archive/*/terraform.json.tfstate", "/webhook/v1/**", "/webhook/v2/**"))
                                .authorizeHttpRequests(authz -> {
                                        authz
                                                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                                                        .requestMatchers("/actuator/**").permitAll()
                                                        .requestMatchers("/error").permitAll()
                                                        .requestMatchers("/callback/v1/**").permitAll()
                                                        .requestMatchers("/webhook/v1/**").permitAll()
                                                        .requestMatchers("/webhook/v2/**").permitAll()
                                                        .requestMatchers("/.well-known/terraform.json").permitAll()
                                                        .requestMatchers("/.well-known/openid-configuration")
                                                        .permitAll()
                                                        .requestMatchers("/.well-known/jwks").permitAll()
                                                        .requestMatchers("/remote/tfe/v2/ping").permitAll()
                                                        .requestMatchers(HttpMethod.PUT,
                                                                        "/remote/tfe/v2/configuration-versions/*")
                                                        .permitAll()
                                                        .requestMatchers(HttpMethod.PUT,
                                                                        "/tfstate/v1/archive/*/terraform.tfstate")
                                                        .permitAll()
                                                        .requestMatchers(HttpMethod.PUT,
                                                                        "/tfstate/v1/archive/*/terraform.json.tfstate")
                                                        .permitAll()
                                                        .requestMatchers("/remote/tfe/v2/plans/logs/**").permitAll()
                                                        .requestMatchers("/remote/tfe/v2/applies/logs/**").permitAll()
                                                        .requestMatchers("/app/*/*/runs/*").permitAll()
                                                        // The WebSocket handshake itself carries no Authorization header - browsers can't set
                                                        // custom headers on a WebSocket upgrade request. Auth happens over the GraphQL-WS
                                                        // connection_init message instead, via AuthenticationWebSocketInterceptor.
                                                        .requestMatchers("/subscriptions").permitAll()
                                        .requestMatchers("/tofu/index.json").permitAll()
                                        .requestMatchers("/terraform/index.json").permitAll()
                                        .anyRequest().authenticated();
                                })
                                .oauth2ResourceServer(oauth2 -> {
                                        AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver = DexAuthenticationManagerResolver
                                                        .builder()
                                                        .dexIssuerUri(issuerUri)
                                                        .patJwtSecret(patJwtSecret)
                                                        .internalJwtSecret(internalJwtSecret)
                                                        .patRepository(patRepository)
                                                        .teamTokenRepository(teamTokenRepository)
                                                        .federatedRepository(federatedRepository)
                                                        .build();
                                        oauth2.authenticationManagerResolver(authenticationManagerResolver);
                                });

                return http.build();
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource(
                        @Value("${io.terrakube.ui.url:http://localhost:3000}") String uiURL) {
                log.info("Loading CORS {}", uiURL);
                CorsConfiguration configuration = new CorsConfiguration();
                configuration.setAllowedOrigins(List.of(uiURL.split(",")));
                configuration.setAllowCredentials(true);
                configuration.setAllowedHeaders(
                                Arrays.asList("Access-Control-Allow-Headers", "Access-Control-Allow-Origin",
                                                "Access-Control-Request-Method", "Access-Control-Request-Headers",
                                                "Origin", "Cache-Control",
                                                "Content-Type", "Accept", "Authorization", "X-TFC-Token", "X-TFC-Url"));
                configuration.setAllowedMethods(Arrays.asList("DELETE", "GET", "POST", "PATCH", "PUT", "OPTIONS"));
                UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
                source.registerCorsConfiguration("/**", configuration);
                return source;
        }
}
