package io.terrakube.api;

import io.restassured.http.Cookie;
import io.restassured.response.Response;
import io.terrakube.api.plugin.token.login.DexExchangeClient;
import io.terrakube.api.plugin.token.login.DexIdentity;
import io.terrakube.api.plugin.token.login.PkceUtil;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Base64;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "io.terrakube.token.login.enabled=true",
    "io.terrakube.token.login.api-url=http://localhost:8080",
    "io.terrakube.token.login.max-days=90"
})
class TerraformLoginBrokerIntegrationTests extends ServerApplicationTests {

    @MockitoBean
    DexExchangeClient dexExchangeClient;

    @org.springframework.beans.factory.annotation.Autowired
    io.terrakube.api.repository.PatRepository patRepository;

    private void stubDex(DexIdentity identity) {
        when(dexExchangeClient.issuerUri()).thenReturn("http://localhost/dex");
        when(dexExchangeClient.buildAuthorizeRedirect(anyString(), anyString()))
            .thenAnswer(inv -> "http://localhost/dex/auth?state=" + inv.getArgument(0));
        when(dexExchangeClient.exchange(anyString(), anyString())).thenReturn(identity);
    }

    @Test
    void endToEndIssuesUsableRevocablePat() {
        stubDex(new DexIdentity("alice@terrakube.io", "Alice", List.of("TERRAKUBE_DEVELOPERS")));

        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);

        Response authz = given().redirects().follow(false)
            .queryParam("client_id", "terraform-cli")
            .queryParam("redirect_uri", "http://localhost:10005/login")
            .queryParam("response_type", "code")
            .queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256")
            .queryParam("state", "cli-xyz")
            .when().get("/oauth/authorize");
        authz.then().statusCode(302).header("Location", containsString("/dex/auth?state="));
        String dexState = authz.getHeader("Location").replaceAll(".*state=", "");

        Response cb = given().redirects().follow(false)
            .queryParam("code", "dex-code").queryParam("state", dexState)
            .when().get("/oauth/callback");
        cb.then().statusCode(302).header("Location", containsString("/oauth/consent"));
        Cookie session = cb.getDetailedCookie("tk_cli_login");

        given().cookie("tk_cli_login", session.getValue())
            .when().get("/oauth/consent")
            .then().statusCode(200).body(containsString("alice@terrakube.io"))
            .body(containsString("name=\"decision\" value=\"authorize\""));

        Response consent = given().redirects().follow(false)
            .cookie("tk_cli_login", session.getValue())
            .header("Origin", "http://localhost:8080")
            .contentType("application/x-www-form-urlencoded")
            .formParam("decision", "authorize").formParam("days", "45").formParam("name", "work-laptop")
            .when().post("/oauth/consent");
        consent.then().statusCode(302)
            .header("Location", startsWith("http://localhost:10005/login?code="))
            .header("Location", containsString("state=cli-xyz"));
        String code = consent.getHeader("Location").replaceAll(".*code=([^&]+).*", "$1");

        String token = given()
            .contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", code)
            .formParam("code_verifier", verifier)
            .formParam("redirect_uri", "http://localhost:10005/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token")
            .then().statusCode(200)
            .body("token_type", equalTo("Bearer"))
            .body("expires_in", equalTo(45 * 86400))
            .extract().path("access_token");

        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(200);

        String jti = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]))
            .replaceAll(".*\"jti\":\"([^\"]+)\".*", "$1");
        io.terrakube.api.rs.token.pat.Pat pat =
            patRepository.findById(java.util.UUID.fromString(jti)).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals("work-laptop", pat.getDescription());
        org.junit.jupiter.api.Assertions.assertEquals("alice@terrakube.io", pat.getCreatedBy());

        given().header("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
            .when().delete("/pat/v1/" + jti).then().statusCode(anyOf(is(202), is(200)));
        given().header("Authorization", "Bearer " + token)
            .when().get("/api/v1/organization/d9b58bd3-f3fc-4056-a026-1163297e80a8")
            .then().statusCode(401);
    }

    @Test
    void reusedAuthCodeIsRejected() {
        stubDex(new DexIdentity("a@terrakube.io", "A", List.of("TERRAKUBE_DEVELOPERS")));
        String verifier = PkceUtil.generateCodeVerifier();
        String challenge = PkceUtil.codeChallengeS256(verifier);
        Response authz = given().redirects().follow(false)
            .queryParam("client_id", "terraform-cli").queryParam("redirect_uri", "http://localhost:10005/login")
            .queryParam("response_type", "code").queryParam("code_challenge", challenge)
            .queryParam("code_challenge_method", "S256").queryParam("state", "s2")
            .when().get("/oauth/authorize");
        String dexState = authz.getHeader("Location").replaceAll(".*state=", "");
        Response cb = given().redirects().follow(false)
            .queryParam("code", "c").queryParam("state", dexState).when().get("/oauth/callback");
        String cookie = cb.getDetailedCookie("tk_cli_login").getValue();
        Response consent = given().redirects().follow(false).cookie("tk_cli_login", cookie)
            .header("Origin", "http://localhost:8080")
            .contentType("application/x-www-form-urlencoded")
            .formParam("decision", "authorize").formParam("days", "30")
            .when().post("/oauth/consent");
        String code = consent.getHeader("Location").replaceAll(".*code=([^&]+).*", "$1");
        given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", code)
            .formParam("code_verifier", verifier).formParam("redirect_uri", "http://localhost:10005/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token").then().statusCode(200);
        given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", code)
            .formParam("code_verifier", verifier).formParam("redirect_uri", "http://localhost:10005/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token").then().statusCode(400).body("error", equalTo("invalid_grant"));
    }
}
