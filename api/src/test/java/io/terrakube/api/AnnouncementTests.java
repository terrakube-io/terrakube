package io.terrakube.api;

import io.terrakube.api.repository.AnnouncementRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScheduleType;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import io.terrakube.api.rs.announcement.AnnouncementSeverity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;

class AnnouncementTests extends ServerApplicationTests {

    private static final String SIMPLE_ANNOUNCEMENTS_ORG_ID = "80000000-0000-0000-0000-000000000001";
    private static final String OTHER_ORG_ID = "70000000-0000-0000-0000-000000000001"; // simple-governance
    private static final String SEEDED_GLOBAL_ID = "80000000-0000-0000-0000-000000000101";
    private static final String SEEDED_ORG_CRITICAL_ID = "80000000-0000-0000-0000-000000000102";
    private static final String JSON_API_CONTENT_TYPE = "application/vnd.api+json";

    @Autowired
    private AnnouncementRepository announcementRepository;

    private final List<UUID> createdAnnouncements = new ArrayList<>();
    private final List<UUID> createdOrganizations = new ArrayList<>();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    }

    @AfterEach
    void cleanup() {
        createdAnnouncements.forEach(id -> {
            if (announcementRepository.existsById(id)) {
                announcementRepository.deleteById(id);
            }
        });
        createdAnnouncements.clear();

        createdOrganizations.forEach(id -> {
            if (organizationRepository.existsById(id)) {
                organizationRepository.deleteById(id);
            }
        });
        createdOrganizations.clear();
    }

    @Test
    void seededDemoDataAndPublicLoginEndpointReturnOnlyLoginGlobalAnnouncements() {
        assertThat(organizationRepository.findById(UUID.fromString(SIMPLE_ANNOUNCEMENTS_ORG_ID)))
                .isPresent()
                .get()
                .extracting(Organization::getName)
                .isEqualTo("simple-announcements");

        given()
                .when()
                .get("/public/v1/announcements")
                .then()
                .assertThat()
                .statusCode(HttpStatus.OK.value())
                .header("X-Server-Time", notNullValue())
                .header("Cache-Control", equalTo("no-store"))
                .body("serverTime", notNullValue())
                .body("announcements.id", hasItem(SEEDED_GLOBAL_ID))
                .body("announcements.id", not(hasItem(SEEDED_ORG_CRITICAL_ID)))
                .body("announcements[0].organization", nullValue())
                .body("announcements[0].createdBy", nullValue())
                .body("announcements[0].updatedBy", nullValue());
    }

    @Test
    void superuserCanCrudGlobalAnnouncementIncludingDisplayOnLogin() {
        String id = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Planned API Upgrade",
                              "message": "Read the **upgrade guide** at [status](https://status.terrakube.io).",
                              "severity": "WARNING",
                              "scope": "GLOBAL",
                              "scheduleType": "IMMEDIATE",
                              "active": true,
                              "dismissible": true,
                              "displayOnLogin": true
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/announcement")
                .then()
                .assertThat()
                .statusCode(HttpStatus.CREATED.value())
                .header("X-Server-Time", notNullValue())
                .body("data.attributes.scope", equalTo("GLOBAL"))
                .body("data.attributes.displayOnLogin", equalTo(true))
                .extract()
                .path("data.id");
        createdAnnouncements.add(UUID.fromString(id));

        // Appears on public login endpoint
        given()
                .when()
                .get("/public/v1/announcements")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("announcements.id", hasItem(id));

        String initialUpdatedDate = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/announcement/" + id)
                .then()
                .statusCode(HttpStatus.OK.value())
                .extract()
                .path("data.attributes.updatedDate");

        try {
            Thread.sleep(1050);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        // Update bumps updatedDate
        String patchedUpdatedDate = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "id": "%s",
                            "attributes": {
                              "title": "Planned API Upgrade (Extended)",
                              "severity": "CRITICAL",
                              "dismissible": false
                            }
                          }
                        }
                        """.formatted(id))
                .when()
                .patch("/api/v1/announcement/" + id)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value())
                .extract()
                .asString();
        assertThat(patchedUpdatedDate).isEmpty();

        String newUpdatedDate = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/announcement/" + id)
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.attributes.title", equalTo("Planned API Upgrade (Extended)"))
                .body("data.attributes.severity", equalTo("CRITICAL"))
                .body("data.attributes.dismissible", equalTo(false))
                .extract()
                .path("data.attributes.updatedDate");
        assertThat(Instant.parse(newUpdatedDate)).isAfter(Instant.parse(initialUpdatedDate));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .delete("/api/v1/announcement/" + id)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
        createdAnnouncements.remove(UUID.fromString(id));
    }

    @Test
    void orgAdminCannotCreateUpdateOrDeleteGlobalAnnouncements() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Unauthorized Global Banner",
                              "message": "Should fail",
                              "severity": "INFO",
                              "scope": "GLOBAL",
                              "scheduleType": "IMMEDIATE",
                              "active": true,
                              "dismissible": true,
                              "displayOnLogin": true
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "id": "%s",
                            "attributes": {
                              "title": "Tampered Title"
                            }
                          }
                        }
                        """.formatted(SEEDED_GLOBAL_ID))
                .when()
                .patch("/api/v1/announcement/" + SEEDED_GLOBAL_ID)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"))
                .when()
                .delete("/api/v1/announcement/" + SEEDED_GLOBAL_ID)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void orgAdminCanManageOwnOrgAnnouncementsAndIsIsolatedFromOtherOrgs() {
        // Org admin creates org announcement via nested org path
        String ownId = given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Org Maintenance Window",
                              "message": "Scheduled **maintenance** in org.",
                              "severity": "WARNING",
                              "scope": "ORGANIZATION",
                              "scheduleType": "SCHEDULED",
                              "startsAt": "2026-01-01T10:00:00Z",
                              "endsAt": "2026-01-01T12:00:00Z",
                              "active": true,
                              "dismissible": true,
                              "displayOnLogin": false
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID + "/announcement")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .body("data.attributes.scope", equalTo("ORGANIZATION"))
                .body("data.relationships.organization.data.id", equalTo(SIMPLE_ANNOUNCEMENTS_ORG_ID))
                .extract()
                .path("data.id");
        createdAnnouncements.add(UUID.fromString(ownId));

        // Org admin updates own org announcement (omitting superuser-only scope/organization)
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "id": "%s",
                            "attributes": {
                              "title": "Org Maintenance Window Updated",
                              "active": false
                            }
                          }
                        }
                        """.formatted(ownId))
                .when()
                .patch("/api/v1/announcement/" + ownId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        // Org admin cannot escalate scope to GLOBAL
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "id": "%s",
                            "attributes": {
                              "scope": "GLOBAL"
                            }
                          }
                        }
                        """.formatted(ownId))
                .when()
                .patch("/api/v1/announcement/" + ownId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Org admin cannot repoint organization to another tenant
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "id": "%s",
                            "relationships": {
                              "organization": {
                                "data": { "type": "organization", "id": "%s" }
                              }
                            }
                          }
                        }
                        """.formatted(ownId, OTHER_ORG_ID))
                .when()
                .patch("/api/v1/announcement/" + ownId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Create an announcement in OTHER_ORG as superuser to verify cross-tenant isolation
        String otherOrgAnnouncementId = given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Governance Secret Notice",
                              "message": "Only visible to simple-governance",
                              "severity": "INFO",
                              "scope": "ORGANIZATION",
                              "scheduleType": "IMMEDIATE",
                              "active": true,
                              "dismissible": true,
                              "displayOnLogin": false
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/organization/" + OTHER_ORG_ID + "/announcement")
                .then()
                .statusCode(HttpStatus.CREATED.value())
                .extract()
                .path("data.id");
        createdAnnouncements.add(UUID.fromString(otherOrgAnnouncementId));

        // ANNOUNCEMENT_ORG_ADMIN (only in simple-announcements) cannot read/update/delete/create in OTHER_ORG
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"))
                .when()
                .get("/api/v1/announcement/" + otherOrgAnnouncementId)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"))
                .when()
                .get("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.id", hasItem(SEEDED_GLOBAL_ID))
                .body("data.id", hasItem(ownId))
                .body("data.id", not(hasItem(otherOrgAnnouncementId)));

        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Cross Tenant",
                              "message": "Should fail",
                              "severity": "INFO",
                              "scope": "ORGANIZATION",
                              "scheduleType": "IMMEDIATE"
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/organization/" + OTHER_ORG_ID + "/announcement")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        // Org admin can delete their own org announcement
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"))
                .when()
                .delete("/api/v1/announcement/" + ownId)
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());
        createdAnnouncements.remove(UUID.fromString(ownId));
    }

    @Test
    void developerWriteTeamCanReadButCannotModifyAnnouncements() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.size()", greaterThanOrEqualTo(4));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Developer Should Fail",
                              "message": "Write team is not org admin",
                              "severity": "INFO",
                              "scope": "ORGANIZATION",
                              "scheduleType": "IMMEDIATE"
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID + "/announcement")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .delete("/api/v1/announcement/" + SEEDED_ORG_CRITICAL_ID)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void internalMachineTokensFailClosedOnAnnouncementEndpoints() {
        given()
                .headers("Authorization", "Bearer " + generateExecutorToken())
                .when()
                .get("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.OK.value())
                .body("data.size()", equalTo(0));

        given()
                .headers("Authorization", "Bearer " + generateExecutorToken())
                .when()
                .get("/api/v1/announcement/" + SEEDED_GLOBAL_ID)
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());

        given()
                .headers("Authorization", "Bearer " + generateExecutorToken(), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Internal Should Fail",
                              "message": "No internal access",
                              "severity": "INFO",
                              "scope": "GLOBAL",
                              "scheduleType": "IMMEDIATE"
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void validationHookEnforcesTitleLengthScheduleAndScopeInvariants() {
        String over120Title = "A".repeat(121);
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "%s",
                              "message": "Valid message",
                              "severity": "INFO",
                              "scope": "GLOBAL",
                              "scheduleType": "IMMEDIATE"
                            }
                          }
                        }
                        """.formatted(over120Title))
                .when()
                .post("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value());

        // SCHEDULED without dates
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Missing dates",
                              "message": "Valid message",
                              "severity": "WARNING",
                              "scope": "GLOBAL",
                              "scheduleType": "SCHEDULED"
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("errors[0].detail", containsString("startsAt"));

        // SCHEDULED with endsAt <= startsAt
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Reversed dates",
                              "message": "Valid message",
                              "severity": "WARNING",
                              "scope": "GLOBAL",
                              "scheduleType": "SCHEDULED",
                              "startsAt": "2026-05-02T10:00:00Z",
                              "endsAt": "2026-05-02T09:00:00Z"
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/announcement")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("errors[0].detail", containsString("endsAt"));

        // GLOBAL linked to organization
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Global with Org",
                              "message": "Invalid",
                              "severity": "INFO",
                              "scope": "GLOBAL",
                              "scheduleType": "IMMEDIATE"
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID + "/announcement")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("errors[0].detail", containsString("organization"));

        // ORGANIZATION with displayOnLogin=true
        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "announcement",
                            "attributes": {
                              "title": "Org Login",
                              "message": "Invalid",
                              "severity": "INFO",
                              "scope": "ORGANIZATION",
                              "scheduleType": "IMMEDIATE",
                              "displayOnLogin": true
                            }
                          }
                        }
                        """)
                .when()
                .post("/api/v1/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID + "/announcement")
                .then()
                .statusCode(HttpStatus.BAD_REQUEST.value())
                .body("errors[0].detail", containsString("global"));
    }

    @Test
    void permissionsEndpointsReportSuperUserAndManageAnnouncements() {
        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/access-token/v1/teams/permissions/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID)
                .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("superUser", equalTo(true))
                .body("manageAnnouncements", equalTo(true));

        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"))
                .when()
                .get("/access-token/v1/teams/permissions/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID)
                .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("superUser", equalTo(false))
                .body("manageAnnouncements", equalTo(true));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when()
                .get("/access-token/v1/teams/permissions/organization/" + SIMPLE_ANNOUNCEMENTS_ORG_ID)
                .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("superUser", equalTo(false))
                .body("manageAnnouncements", equalTo(false));

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when()
                .get("/access-token/v1/teams/current-teams")
                .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("superUser", equalTo(true));

        given()
                .headers("Authorization", "Bearer " + generatePAT("ANNOUNCEMENT_ORG_ADMIN"))
                .when()
                .get("/access-token/v1/teams/current-teams")
                .then()
                .statusCode(HttpStatus.ACCEPTED.value())
                .body("superUser", equalTo(false));
    }

    @Test
    void disablingOrganizationCascadesDeletionOfOrgAnnouncements() {
        Organization tempOrg = new Organization();
        tempOrg.setName("temp-ann-" + UUID.randomUUID().toString().substring(0, 8));
        tempOrg.setDescription("Temporary org for soft-delete cascade test");
        tempOrg = organizationRepository.save(tempOrg);
        createdOrganizations.add(tempOrg.getId());

        Announcement announcement = new Announcement();
        announcement.setTitle("Temp Org Announcement");
        announcement.setMessage("Will be purged on disable");
        announcement.setSeverity(AnnouncementSeverity.INFO);
        announcement.setScope(AnnouncementScope.ORGANIZATION);
        announcement.setScheduleType(AnnouncementScheduleType.IMMEDIATE);
        announcement.setOrganization(tempOrg);
        announcement.setActive(true);
        announcement.setDismissible(true);
        announcement.setDisplayOnLogin(false);
        announcement.setCreatedDate(new Date());
        announcement = announcementRepository.save(announcement);
        UUID announcementId = announcement.getId();
        createdAnnouncements.add(announcementId);

        given()
                .headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"), "Content-Type", JSON_API_CONTENT_TYPE)
                .body("""
                        {
                          "data": {
                            "type": "organization",
                            "id": "%s",
                            "attributes": {
                              "disabled": true
                            }
                          }
                        }
                        """.formatted(tempOrg.getId()))
                .when()
                .patch("/api/v1/organization/" + tempOrg.getId())
                .then()
                .statusCode(HttpStatus.NO_CONTENT.value());

        assertThat(announcementRepository.findById(announcementId)).isEmpty();
        createdAnnouncements.remove(announcementId);
    }
}
