package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.mockito.Mockito.when;

public class IndexTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void terraformIndexSearch() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/terraform/index.json")
                .then()
                .assertThat()
                //.log() dont show terraform response it is a lot of data
                //.all()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void tofuIndexSearch() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tofu/index.json")
                .then()
                .assertThat()
                //.log() dont show terraform response it is a lot of data
                //.all()
                .statusCode(HttpStatus.OK.value());
    }
}
