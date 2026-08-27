package io.terrakube.api;

import io.terrakube.api.repository.FederatedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.HttpStatus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * The federated provider lookup sits inside entity-level {@code @ReadPermission} checks, which Elide
 * evaluates once per record — and Elide materializes and permission-checks every member of a to-many
 * relationship just to emit its {@code {type, id}} linkage. Unmemoized, that made the query count
 * scale with total organization activity rather than with anything about the requested resource.
 *
 * <p>These tests pin the invariant that matters: lookup count is flat with respect to response size.
 * The absolute count is not asserted because the authentication filter performs its own lookup, once
 * per filter chain pass, outside any request-bound scope.
 */
class FederatedLookupTests extends ServerApplicationTests {

    private static final String SIMPLE_ORGANIZATION = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final String SIMPLE_WORKSPACE = "5ed411ca-7ab8-4d2f-b591-02d0d5788afc";

    @SpyBean
    FederatedRepository federatedRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void includingOrganizationDoesNotAddFederatedLookups() {
        long withoutInclude = countFederatedLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace/" + SIMPLE_WORKSPACE);
        long withInclude = countFederatedLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace/" + SIMPLE_WORKSPACE
                        + "?include=organization");

        assertEquals(withoutInclude, withInclude,
                "attaching the organization must not re-run the federated lookup per related record");
    }

    @Test
    void listingWorkspacesDoesNotAddFederatedLookupsPerRecord() {
        long singleWorkspace = countFederatedLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace/" + SIMPLE_WORKSPACE);
        long allWorkspaces = countFederatedLookups(
                "/api/v1/organization/" + SIMPLE_ORGANIZATION + "/workspace");

        assertEquals(singleWorkspace, allWorkspaces,
                "listing every workspace must cost the same federated lookups as fetching one");
    }

    private long countFederatedLookups(String path) {
        clearInvocations(federatedRepository);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get(path)
                .then()
                .statusCode(HttpStatus.OK.value());

        return mockingDetails(federatedRepository).getInvocations().stream()
                .filter(invocation -> "findByIssuerUrlAndAudience".equals(invocation.getMethod().getName()))
                .count();
    }
}
