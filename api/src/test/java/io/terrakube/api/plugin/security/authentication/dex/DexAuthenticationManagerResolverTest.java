package io.terrakube.api.plugin.security.authentication.dex;

import io.terrakube.api.repository.FederatedRepository;
import io.terrakube.api.repository.PatRepository;
import io.terrakube.api.repository.TeamTokenRepository;
import io.terrakube.api.rs.federated.Federated;
import io.terrakube.api.rs.federated.claim.FederatedClaim;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;

import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DexAuthenticationManagerResolverTest {

    @Mock
    PatRepository patRepository;

    @Mock
    TeamTokenRepository teamTokenRepository;

    @Mock
    FederatedRepository federatedRepository;

    private HttpServletRequest dexTokenRequest() {
        String jti = UUID.randomUUID().toString();
        return tokenRequest("{\"iss\":\"https://dummy-dex-issuer\",\"aud\":\"terrakube\",\"jti\":\"" + jti + "\"}");
    }

    private HttpServletRequest tokenRequest(String payloadJson) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes());
        String token = "header." + payload + ".signature";

        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("authorization")).thenReturn("Bearer " + token);
        return request;
    }

    private DexAuthenticationManagerResolver newResolver() {
        return DexAuthenticationManagerResolver.builder()
                .dexIssuerUri("https://dummy-dex-issuer")
                .patJwtSecret(Base64.getUrlEncoder().encodeToString("pat-secret-pat-secret-pat-secret".getBytes()))
                .internalJwtSecret(Base64.getUrlEncoder().encodeToString("internal-secret-internal-secret".getBytes()))
                .patRepository(patRepository)
                .teamTokenRepository(teamTokenRepository)
                .federatedRepository(federatedRepository)
                .build();
    }

    @Test
    void reusesTheDecoderAcrossRequestsInsteadOfRefetchingIssuerMetadataEveryTime() {
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());

        DexAuthenticationManagerResolver resolver = newResolver();
        JwtDecoder decoder = mock(JwtDecoder.class);

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer")).thenReturn(decoder);

            AuthenticationManager first = resolver.resolve(dexTokenRequest());
            AuthenticationManager second = resolver.resolve(dexTokenRequest());

            assertThat(first).isNotNull();
            assertThat(second).isNotNull();
            jwtDecoders.verify(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"), Mockito.times(1));
        }
    }

    @Test
    void translatesAnUnreachableIssuerIntoAnAuthenticationExceptionInsteadOfALeakingRuntimeException() {
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());

        DexAuthenticationManagerResolver resolver = newResolver();

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"))
                    .thenThrow(new IllegalStateException("Connection refused"));

            assertThatThrownBy(() -> resolver.resolve(dexTokenRequest()))
                    .isInstanceOf(AuthenticationServiceException.class);
        }
    }

    @Test
    void retriesOnTheNextRequestAfterAFailedFetchInsteadOfCachingTheFailure() {
        when(federatedRepository.findByIssuerUrlAndAudience(anyString(), anyString())).thenReturn(Optional.empty());

        DexAuthenticationManagerResolver resolver = newResolver();
        JwtDecoder decoder = mock(JwtDecoder.class);

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"))
                    .thenThrow(new IllegalStateException("Connection refused"))
                    .thenReturn(decoder);

            assertThatThrownBy(() -> resolver.resolve(dexTokenRequest()))
                    .isInstanceOf(AuthenticationServiceException.class);

            AuthenticationManager recovered = resolver.resolve(dexTokenRequest());

            assertThat(recovered).isNotNull();
            jwtDecoders.verify(() -> JwtDecoders.fromIssuerLocation("https://dummy-dex-issuer"), Mockito.times(2));
        }
    }

    @Test
    void acceptsExternalTokenWithNonUuidIdAndMatchingAudienceInAList() {
        String issuer = "https://token.actions.githubusercontent.com";
        Federated federated = federated(issuer, "terrakube", "repository", "acme/infra");
        when(federatedRepository.findByIssuerUrlAndAudience(issuer, "other-service")).thenReturn(Optional.empty());
        when(federatedRepository.findByIssuerUrlAndAudience(issuer, "terrakube")).thenReturn(Optional.of(federated));
        JwtDecoder decoder = mock(JwtDecoder.class);

        HttpServletRequest request = tokenRequest("{\"iss\":\"" + issuer
                + "\",\"aud\":[\"other-service\",\"terrakube\"],\"jti\":\"provider-specific-id\""
                + ",\"repository\":\"acme/infra\"}");

        try (MockedStatic<JwtDecoders> jwtDecoders = Mockito.mockStatic(JwtDecoders.class)) {
            jwtDecoders.when(() -> JwtDecoders.fromIssuerLocation(issuer)).thenReturn(decoder);

            assertThat(newResolver().resolve(request)).isNotNull();

            jwtDecoders.verify(() -> JwtDecoders.fromIssuerLocation(issuer), Mockito.times(1));
            verify(patRepository, never()).findById(any());
            verify(teamTokenRepository, never()).findById(any());
        }
    }

    @Test
    void rejectsFederatedTokenWhenConfiguredClaimsDoNotMatch() {
        String issuer = "https://token.actions.githubusercontent.com";
        when(federatedRepository.findByIssuerUrlAndAudience(issuer, "terrakube"))
                .thenReturn(Optional.of(federated(issuer, "terrakube", "repository", "acme/infra")));

        HttpServletRequest request = tokenRequest("{\"iss\":\"" + issuer
                + "\",\"aud\":\"terrakube\",\"repository\":\"attacker/infra\"}");

        assertThatThrownBy(() -> newResolver().resolve(request))
                .isInstanceOf(BadCredentialsException.class)
                .hasMessage("Federated token is not authorized");
    }

    private Federated federated(String issuer, String audience, String claimKey, String claimValue) {
        Federated federated = new Federated();
        federated.setIssuerUrl(issuer);
        federated.setAudience(audience);
        FederatedClaim claim = new FederatedClaim();
        claim.setClaimKey(claimKey);
        claim.setClaimValue(claimValue);
        federated.setClaims(List.of(claim));
        return federated;
    }
}
