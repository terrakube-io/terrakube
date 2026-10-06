package io.terrakube.api;

import io.terrakube.api.plugin.scheduler.trigger.RunTriggerEventTransactions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.Mockito.when;

class RunTriggerEventOperationsControllerTest extends ServerApplicationTests {

    @MockitoBean
    RunTriggerEventTransactions transactions;

    @Test
    void replayRequiresAdminGroup() {
        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
                .when().post("/admin/v1/run-trigger-events/" + UUID.randomUUID() + "/replay")
                .then().statusCode(HttpStatus.FORBIDDEN.value());
    }

    @Test
    void replayRearmsAFailedEvent() {
        UUID eventId = UUID.randomUUID();
        when(transactions.rearmForRetry(eventId)).thenReturn(true);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/run-trigger-events/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value())
                .body("eventId", equalTo(eventId.toString()))
                .body("rearmed", equalTo(true));
    }

    @Test
    void replayOfANonFailedEventIsANoOpNotAnError() {
        UUID eventId = UUID.randomUUID();
        when(transactions.rearmForRetry(eventId)).thenReturn(false);

        given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
                .when().post("/admin/v1/run-trigger-events/" + eventId + "/replay")
                .then().statusCode(HttpStatus.OK.value())
                .body("rearmed", equalTo(false));
    }
}
