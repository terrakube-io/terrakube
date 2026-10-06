package io.terrakube.api;

import io.terrakube.api.repository.TagRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.tag.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.Mockito.when;

// Elide returns at most its default page size (500) when a client does not paginate, and the Organization read
// permission is evaluated in memory so clients cannot paginate explicitly. Organizations with more than 500
// tags were silently truncated in both the JSON:API relationship endpoint and the GraphQL query the UI
// uses (issue #3609). Tag carries @Paginate to raise that ceiling; this test guards it.
class TagPaginationTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "d9b58bd3-f3fc-4056-a026-1163297e80a8";
    private static final int SEEDED_TAGS = 600;

    @Autowired
    private TagRepository tagRepository;

    @BeforeEach
    public void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @Test
    void listMoreThanFiveHundredTagsViaJsonApiAndGraphQl() {
        List<Tag> seeded = seedTags();
        try {
            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .when()
                    .get("/api/v1/organization/" + ORGANIZATION_ID + "/tag")
                    .then()
                    .assertThat()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.OK.value())
                    .body("data.size()", greaterThanOrEqualTo(SEEDED_TAGS));

            String query = "{ organization(ids: [\"" + ORGANIZATION_ID + "\"]) { edges { node { "
                    + "tag { edges { node { id name } } } } } } }";

            given()
                    .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                    .contentType("application/json")
                    .body(Map.of("query", query))
                    .when()
                    .post("/graphql/api/v1")
                    .then()
                    .assertThat()
                    .log()
                    .ifValidationFails()
                    .statusCode(HttpStatus.OK.value())
                    .body("data.organization.edges[0].node.tag.edges.size()",
                            greaterThanOrEqualTo(SEEDED_TAGS));
        } finally {
            tagRepository.deleteAll(seeded);
        }
    }

    private List<Tag> seedTags() {
        Organization organization = organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).get();
        List<Tag> tags = new ArrayList<>(SEEDED_TAGS);
        for (int i = 0; i < SEEDED_TAGS; i++) {
            Tag tag = new Tag();
            tag.setName(String.format("pagination-3609-%04d", i));
            tag.setOrganization(organization);
            tags.add(tag);
        }
        return tagRepository.saveAll(tags);
    }
}
