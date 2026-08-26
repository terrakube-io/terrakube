package io.terrakube.executor.configuration.security;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManagerResolver;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true, securedEnabled = true, jsr250Enabled = true)
public class SecurityAdapter {

    @Bean
    @Order(1)
    public SecurityFilterChain filterChain(HttpSecurity http, @Value("${io.terrakube.client.secretKey}") String internalJwtSecret) throws Exception {
        http.cors(Customizer.withDefaults())
                .authorizeHttpRequests(authz -> {
                    authz.requestMatchers("/actuator/**").permitAll()
                            .anyRequest().authenticated();
                })
                .oauth2ResourceServer(oauth2 -> {
                    AuthenticationManagerResolver<HttpServletRequest> authenticationManagerResolver = ExecutorManagerResolver
                            .builder()
                            .internalJwtSecret(internalJwtSecret)
                            .build();
                    oauth2.authenticationManagerResolver(authenticationManagerResolver);
                });

        return http.build();
    }
}
