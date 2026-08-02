package io.terrakube.api.plugin.subscription;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.server.WebSocketGraphQlInterceptor;
import org.springframework.graphql.server.support.BearerTokenAuthenticationExtractor;
import org.springframework.graphql.server.webmvc.AuthenticationWebSocketInterceptor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;

@Configuration
public class SubscriptionWebSocketConfiguration {

    @Bean
    public WebSocketGraphQlInterceptor authenticationWebSocketInterceptor(
            @Value("${io.terrakube.token.issuer-uri}") String dexIssuerUri) {
        AuthenticationManager authenticationManager =
                new ProviderManager(new JwtAuthenticationProvider(new LazyIssuerJwtDecoder(dexIssuerUri)));
        return new AuthenticationWebSocketInterceptor(new BearerTokenAuthenticationExtractor(), authenticationManager);
    }

    /**
     * Defers the OIDC discovery fetch (JwtDecoders.fromIssuerLocation makes an HTTP call the first time
     * it's used) until a WebSocket connection actually needs to authenticate. Spring for GraphQL's
     * auto-configuration collects every WebSocketGraphQlInterceptor bean into a list at application startup,
     * which forces eager instantiation of the interceptor/AuthenticationManager regardless of {@code @Lazy}
     * on the bean method - so the laziness has to live inside the JwtDecoder itself instead. Matches
     * DexAuthenticationManagerResolver's existing per-request (not eager) resolution: a transient Dex
     * outage shouldn't be able to prevent the whole app from starting.
     */
    private static final class LazyIssuerJwtDecoder implements JwtDecoder {

        private final String issuerUri;
        private volatile JwtDecoder delegate;

        private LazyIssuerJwtDecoder(String issuerUri) {
            this.issuerUri = issuerUri;
        }

        @Override
        public Jwt decode(String token) throws JwtException {
            JwtDecoder resolved = delegate;
            if (resolved == null) {
                synchronized (this) {
                    resolved = delegate;
                    if (resolved == null) {
                        resolved = JwtDecoders.fromIssuerLocation(issuerUri);
                        delegate = resolved;
                    }
                }
            }
            return resolved.decode(token);
        }
    }
}
