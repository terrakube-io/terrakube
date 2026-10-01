package io.terrakube.api.plugin.token.login;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

@Component
public class DexExchangeClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TerraformLoginProperties loginProperties;
    private final String issuerUri;
    private final String tokenUrl;
    private final String dexClientId;
    private final JwtDecoder jwtDecoder;
    private final WebClient webClient = WebClient.builder()
        .clientConnector(new org.springframework.http.client.reactive.ReactorClientHttpConnector(
            reactor.netty.http.client.HttpClient.create()
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 5_000)
                .responseTimeout(java.time.Duration.ofSeconds(10))))
        .build();

    public DexExchangeClient(TerraformLoginProperties loginProperties,
                             @Value("${io.terrakube.token.issuer-uri}") String issuerUri,
                             @Value("${io.terrakube.token.login.dex-token-url:}") String dexTokenUrl,
                             @Value("${io.terrakube.token.login.dex-jwk-set-url:}") String dexJwkSetUrl,
                             @Value("${io.terrakube.token.client-id}") String dexClientId) {
        this.loginProperties = loginProperties;
        this.issuerUri = issuerUri.endsWith("/") ? issuerUri.substring(0, issuerUri.length() - 1) : issuerUri;
        this.tokenUrl = dexTokenUrl == null || dexTokenUrl.isBlank()
            ? this.issuerUri + "/token"
            : dexTokenUrl;
        this.dexClientId = dexClientId;
        String jwkSetUrl = dexJwkSetUrl == null || dexJwkSetUrl.isBlank()
            ? this.issuerUri + "/keys"
            : dexJwkSetUrl;
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUrl).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(this.issuerUri));
        this.jwtDecoder = decoder;
    }

    public String issuerUri() {
        return issuerUri;
    }

    public String buildAuthorizeRedirect(String state, String codeChallenge) {
        String q = "response_type=code"
            + "&client_id=" + enc(dexClientId)
            + "&redirect_uri=" + enc(loginProperties.getCallbackUrl())
            + "&scope=" + enc("openid profile email groups")
            + "&code_challenge=" + enc(codeChallenge)
            + "&code_challenge_method=S256"
            + "&state=" + enc(state);
        return issuerUri + "/auth?" + q;
    }

    public DexIdentity exchange(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", loginProperties.getCallbackUrl());
        form.add("client_id", dexClientId);
        form.add("code_verifier", codeVerifier);

        String body;
        try {
            body = webClient.post().uri(tokenUrl)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .bodyValue(form)
                .retrieve()
                .bodyToMono(String.class)
                .block();
        } catch (Exception e) {
            throw new BrokerUpstreamException("Dex token exchange failed", e);
        }

        try {
            JsonNode json = MAPPER.readTree(body);
            String idToken = json.path("id_token").asText(null);
            if (idToken == null) {
                throw new BrokerUpstreamException("Dex response missing id_token");
            }
            Jwt claims = jwtDecoder.decode(idToken);
            if (!claims.getAudience().contains(dexClientId)) {
                throw new BrokerUpstreamException("id_token audience mismatch");
            }
            String email = claims.getClaimAsString("email");
            if (email == null || email.isBlank()) {
                throw new BrokerUpstreamException("id_token missing email");
            }
            List<String> groups = claims.getClaimAsStringList("groups");
            return new DexIdentity(
                email,
                claims.getClaimAsString("name"),
                groups == null ? List.of() : new ArrayList<>(groups));
        } catch (BrokerUpstreamException e) {
            throw e;
        } catch (JwtException e) {
            throw new BrokerUpstreamException("Dex id_token validation failed", e);
        } catch (Exception e) {
            throw new BrokerUpstreamException("Could not parse Dex id_token", e);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
