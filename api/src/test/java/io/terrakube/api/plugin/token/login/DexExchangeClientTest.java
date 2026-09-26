package io.terrakube.api.plugin.token.login;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.*;

class DexExchangeClientTest {

    WireMockServer wm;
    DexExchangeClient client;
    String issuer;
    RSAKey signingKey;

    @BeforeEach
    void setUp() throws Exception {
        wm = new WireMockServer(options().dynamicPort());
        wm.start();
        issuer = "http://localhost:" + wm.port() + "/dex";
        KeyPair keyPair = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        signingKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
            .privateKey((RSAPrivateKey) keyPair.getPrivate()).keyID("test-key").build();
        wm.stubFor(get(urlEqualTo("/dex/keys")).willReturn(okJson(
            "{\"keys\":[" + signingKey.toPublicJWK().toJSONString() + "]}")));
        TerraformLoginProperties props = new TerraformLoginProperties();
        props.setEnabled(true);
        props.setApiUrl("https://api.local");
        props.normalize();
        client = new DexExchangeClient(props, issuer, "", "", "terrakube-app");
    }

    @AfterEach
    void tearDown() {
        wm.stop();
    }

    @Test
    void buildAuthorizeRedirectContainsAllParams() {
        String url = client.buildAuthorizeRedirect("state-123", "challenge-abc");
        assertTrue(url.startsWith(issuer + "/auth?"));
        assertTrue(url.contains("response_type=code"));
        assertTrue(url.contains("client_id=terrakube-app"));
        assertTrue(url.contains("redirect_uri=https%3A%2F%2Fapi.local%2Foauth%2Fcallback"));
        assertTrue(url.contains("code_challenge=challenge-abc"));
        assertTrue(url.contains("code_challenge_method=S256"));
        assertTrue(url.contains("state=state-123"));
    }

    @Test
    void exchangeParsesIdentityFromIdToken() {
        String idToken = signedIdToken("terrakube-app", "alice@example.io", List.of("DEV", "OPS"));
        wm.stubFor(post(urlEqualTo("/dex/token")).willReturn(okJson(
            "{\"access_token\":\"x\",\"id_token\":\"" + idToken + "\",\"token_type\":\"bearer\"}")));

        DexIdentity id = client.exchange("the-code", "the-verifier");
        assertEquals("alice@example.io", id.email());
        assertEquals("Alice", id.name());
        assertEquals(List.of("DEV", "OPS"), id.groups());
    }

    @Test
    void exchangeThrowsOnUpstreamError() {
        wm.stubFor(post(urlEqualTo("/dex/token")).willReturn(aResponse().withStatus(401)));
        assertThrows(BrokerUpstreamException.class, () -> client.exchange("bad", "v"));
    }

    @Test
    void exchangeRejectsIdTokenForAnotherAudience() {
        String idToken = signedIdToken("another-client", "alice@example.io", List.of("DEV"));
        wm.stubFor(post(urlEqualTo("/dex/token")).willReturn(okJson(
            "{\"access_token\":\"x\",\"id_token\":\"" + idToken + "\"}")));

        assertThrows(BrokerUpstreamException.class, () -> client.exchange("code", "verifier"));
    }

    private String signedIdToken(String audience, String email, List<String> groups) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(issuer).subject("u")
                .audience(audience).claim("email", email).claim("name", "Alice")
                .claim("groups", groups).issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plus(5, ChronoUnit.MINUTES))).build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256)
                .keyID(signingKey.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
