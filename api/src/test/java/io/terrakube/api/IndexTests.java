package io.terrakube.api;

import io.terrakube.api.plugin.json.DownloadReleasesService;
import org.apache.commons.io.FileUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;

import static io.restassured.RestAssured.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

public class IndexTests extends ServerApplicationTests {

    @MockBean
    DownloadReleasesService downloadReleasesService;

    @BeforeEach
    public void setup() throws IOException {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        doAnswer(invocation -> {
            File file = invocation.getArgument(1);
            FileUtils.writeStringToFile(file, "{\"mock\": \"data\"}", Charset.defaultCharset());
            return null;
        }).when(downloadReleasesService).downloadReleasesToFile(anyString(), any(File.class));

        doAnswer(invocation -> {
            File file = invocation.getArgument(1);
            FileUtils.writeStringToFile(file, "{\"mock\": \"data\"}", Charset.defaultCharset());
            return null;
        }).when(downloadReleasesService).downloadReleasesToFile(anyString(), any(File.class), anyString());
    }

    @Test
    void terraformIndexSearch() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/terraform/index.json")
                .then()
                .assertThat()
                .log()
                .all()
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
                .log()
                .all()
                .statusCode(HttpStatus.OK.value());
    }
}
