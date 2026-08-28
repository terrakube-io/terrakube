package io.terrakube.api;

import io.terrakube.api.repository.AccessRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Organization membership is checked once per record, and for a caller who is neither a superuser
 * nor a direct team member it falls through to a workspace access query. Superusers and team members
 * short circuit before that query, so this covers the path they never reach.
 */
class LimitedAccessLookupTests extends ServerApplicationTests {

    private static final String SIMPLE_ORGANIZATION = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String SIMPLE_WORKSPACE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @MockitoSpyBean
    AccessRepository accessRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void includingOrganizationDoesNotAddAccessLookups() {
        long withoutInclude = countAccessLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace/" + SIMPLE_WORKSPACE);
        long withInclude = countAccessLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace/" + SIMPLE_WORKSPACE
                        + "?include=organization");

        assertEquals(withoutInclude, withInclude,
                "attaching the organization must not re-run the access lookup per related record");
    }

    @Test
    void listingWorkspacesDoesNotAddAccessLookupsPerRecord() {
        long singleWorkspace = countAccessLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace/" + SIMPLE_WORKSPACE);
        long allWorkspaces = countAccessLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace");

        assertEquals(singleWorkspace, allWorkspaces,
                "listing every workspace must cost the same access lookups as fetching one");
    }

    private long countAccessLookups(String path) {
        clearInvocations(accessRepository);

        given()
                .headers("Authorization", "Bearer " + generatePAT("LIMITED_ACCESS"))
                .when()
                .get(path)
                .then()
                .statusCode(HttpStatus.OK.value());

        return mockingDetails(accessRepository).getInvocations().stream()
                .filter(invocation ->
                        "findAllByWorkspaceOrganizationIdAndNameIn".equals(invocation.getMethod().getName()))
                .count();
    }
}
