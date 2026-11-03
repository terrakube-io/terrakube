package io.terrakube.api;

import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.JobKey;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;

import io.terrakube.api.plugin.scheduler.module.ModuleRefreshPollerScheduler;
import io.terrakube.api.repository.ModuleRepository;
import io.terrakube.api.rs.module.Module;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

class ModuleSyncControllerTests extends ServerApplicationTests {

    private static final String ORGANIZATION_ID = "f5365c9e-bc11-4781-b649-45a281ccdd4a";
    private static final JobKey POLLER = new JobKey(ModuleRefreshPollerScheduler.PREFIX_MODULE_REFRESH_POLLER);

    @Autowired
    ModuleRepository moduleRepository;

    private UUID moduleId;

    // The real poller would claim the module and change next_refresh under the assertions.
    @BeforeAll
    void pausePoller() throws SchedulerException {
        scheduler.pauseJob(POLLER);
    }

    @AfterAll
    void resumePoller() throws SchedulerException {
        scheduler.resumeJob(POLLER);
    }

    @BeforeEach
    void seedModule() {
        Module module = new Module();
        module.setName("sync-" + UUID.randomUUID().toString().substring(0, 8));
        module.setProvider("aws");
        module.setSource("https://git.example.test/acme/network.git");
        module.setOrganization(organizationRepository.findById(UUID.fromString(ORGANIZATION_ID)).orElseThrow());
        module.setNextRefresh(new Date(System.currentTimeMillis() + 3_600_000L));
        moduleId = moduleRepository.save(module).getId();
    }

    private int sync(String token, String organizationId) {
        return given()
                .headers("Authorization", "Bearer " + token)
                .when()
                .post("/module/v1/organization/" + organizationId + "/module/" + moduleId + "/sync")
                .then()
                .log().ifValidationFails()
                .extract().statusCode();
    }

    private Date nextRefresh() {
        return moduleRepository.findById(moduleId).orElseThrow().getNextRefresh();
    }

    @Test
    void moduleManagerMarksTheModuleDue() {
        assertThat(sync(generatePAT("TERRAKUBE_DEVELOPERS"), ORGANIZATION_ID)).isEqualTo(HttpStatus.ACCEPTED.value());
        assertThat(nextRefresh()).isNull();
    }

    @Test
    void groupWithoutATeamIsForbidden() {
        assertThat(sync(generatePAT("FAKE_DEVELOPERS"), ORGANIZATION_ID)).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(nextRefresh()).isNotNull();
    }

    @Test
    void moduleOfAnotherOrganizationIsForbidden() {
        assertThat(sync(generatePAT("TERRAKUBE_DEVELOPERS"), UUID.randomUUID().toString()))
                .isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(nextRefresh()).isNotNull();
    }

    @Test
    void internalTokenIsForbidden() {
        assertThat(sync(generateRegistryToken(), ORGANIZATION_ID)).isEqualTo(HttpStatus.FORBIDDEN.value());
        assertThat(nextRefresh()).isNotNull();
    }
}
