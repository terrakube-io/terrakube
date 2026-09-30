package io.terrakube.api;

import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;

class TerraformLoginBrokerDisabledTests extends ServerApplicationTests {

    @Test
    void oauthEndpointsAreInvisibleWhenDisabled() {
        given().when().get("/oauth/authorize?client_id=terraform-cli&redirect_uri=http://localhost:10000/login"
                + "&response_type=code&code_challenge=x&code_challenge_method=S256&state=s")
            .then().statusCode(404);
        given().contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "authorization_code").formParam("code", "x")
            .formParam("code_verifier", "y").formParam("redirect_uri", "http://localhost:10000/login")
            .formParam("client_id", "terraform-cli")
            .when().post("/oauth/token").then().statusCode(404);
    }
}
