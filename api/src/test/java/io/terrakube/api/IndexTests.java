package io.terrakube.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.terrakube.api.plugin.json.DownloadReleasesService;

import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class IndexTests extends ServerApplicationTests {

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void terraformIndexSearch() {
        when(redisTemplate.hasKey("terraformReleasesResponse")).thenReturn(true);
        when(valueOperations.get("terraformReleasesResponse")).thenReturn("{}");

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
        when(redisTemplate.hasKey("tofuReleasesResponse")).thenReturn(true);
        when(valueOperations.get("tofuReleasesResponse")).thenReturn("[]");

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

    @Test
    void tofuIndexReturnsStaleCacheWhenReleaseDownloadFails() {
        when(redisTemplate.hasKey("tofuReleasesResponse")).thenReturn(false);
        when(valueOperations.get("tofuReleasesResponseStale")).thenReturn("{\"stale\": true}");
        doThrow(new IllegalStateException("release download timed out"))
                .when(downloadReleasesService).downloadAllReleases(anyString(), any());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tofu/index.json")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body(equalTo("{\"stale\": true}"));
    }

    @Test
    void tofuIndexReturnsServiceUnavailableWhenReleaseDownloadFailsAndNoCacheExists() {
        when(redisTemplate.hasKey("tofuReleasesResponse")).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn(null);
        doThrow(new IllegalStateException("release download timed out"))
                .when(downloadReleasesService).downloadAllReleases(anyString(), any());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tofu/index.json")
                .then()
                .assertThat()
                .statusCode(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    @Test
    void terraformIndexReturnsStaleCacheWhenEndpointFails() {
        when(redisTemplate.hasKey("terraformReleasesResponse")).thenReturn(false);
        when(valueOperations.get("terraformReleasesResponseStale")).thenReturn("{\"stale\": true}");

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/terraform/index.json")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value());
    }

    @Test
    void terraformIndexReturnsServiceUnavailableWhenEndpointFailsAndNoCacheExists() {
        when(redisTemplate.hasKey("terraformReleasesResponse")).thenReturn(false);
        when(valueOperations.get(anyString())).thenReturn(null);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/terraform/index.json")
                .then()
                .assertThat()
                .statusCode(HttpStatus.SERVICE_UNAVAILABLE.value());
    }

    private static ArrayNode releases(String json) throws Exception {
        return (ArrayNode) new ObjectMapper().readTree(json);
    }

    @Test
    void tofuIndexServesAllPagesAndCachesThem() throws Exception {
        when(redisTemplate.hasKey("tofuReleasesResponse")).thenReturn(false);
        when(downloadReleasesService.downloadAllReleases(anyString(), any())).thenReturn(
                new DownloadReleasesService.ReleasesPages(
                        releases("[{\"tag_name\":\"v1.10.0\"},{\"tag_name\":\"v1.6.0\"}]"), true));
        String expected = "[{\"tag_name\":\"v1.10.0\"},{\"tag_name\":\"v1.6.0\"}]";

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tofu/index.json")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body(equalTo(expected));

        verify(valueOperations).set("tofuReleasesResponse", expected);
        verify(redisTemplate).expire(eq("tofuReleasesResponse"), eq(30L), eq(TimeUnit.MINUTES));
        verify(valueOperations).set("tofuReleasesResponseStale", expected);
    }

    @Test
    void tofuIndexCompletesAPartialListWithTheStaleCacheAndCachesItBriefly() throws Exception {
        when(redisTemplate.hasKey("tofuReleasesResponse")).thenReturn(false);
        when(valueOperations.get("tofuReleasesResponseStale")).thenReturn(
                "[{\"tag_name\":\"v1.10.0\",\"old\":true},{\"tag_name\":\"v1.6.0\"}]");
        when(downloadReleasesService.downloadAllReleases(anyString(), any())).thenReturn(
                new DownloadReleasesService.ReleasesPages(
                        releases("[{\"tag_name\":\"v1.11.0\"},{\"tag_name\":\"1.10.0\"}]"), false));
        String expected = "[{\"tag_name\":\"v1.11.0\"},{\"tag_name\":\"1.10.0\"},{\"tag_name\":\"v1.6.0\"}]";

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/tofu/index.json")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .body(equalTo(expected));

        verify(valueOperations).set("tofuReleasesResponse", expected);
        verify(redisTemplate).expire(eq("tofuReleasesResponse"), eq(1L), eq(TimeUnit.MINUTES));
    }
}
