package io.terrakube.api;

import io.terrakube.api.rs.job.step.DeliveryState;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StepDeliveryStateMigrationTest {

    @Test
    void changelogDefinesAllRequiredColumnsAndIndex() throws IOException {
        String xml = Files.readString(
                Path.of("src/main/resources/db/changelog/local/changelog-2.34.0-step-delivery-state.xml"));

        assertTrue(xml.contains("tableName=\"step\""));
        assertTrue(xml.contains("<column name=\"delivery_state\" type=\"varchar(32)\" defaultValue=\"NOT_DURABLE\"/>"));
        assertTrue(xml.contains("<column name=\"delivery_revision\" type=\"bigint\"/>"));
        assertTrue(xml.contains("<column name=\"delivery_timestamp\" type=\"bigint\"/>"));
        assertTrue(xml.contains("<column name=\"lease_owner\" type=\"varchar(255)\"/>"));
        assertTrue(xml.contains("<column name=\"lease_until\" type=\"timestamp\"/>"));
        assertTrue(xml.contains("indexName=\"idx_step_delivery_state\""));
    }

    @Test
    void deliveryStateEnumHasExpectedValues() {
        DeliveryState[] values = DeliveryState.values();
        assertEquals(5, values.length);
        assertEquals(DeliveryState.ACKED, DeliveryState.valueOf("ACKED"));
        assertEquals(DeliveryState.SPOOLED, DeliveryState.valueOf("SPOOLED"));
        assertEquals(DeliveryState.RECOVERED, DeliveryState.valueOf("RECOVERED"));
        assertEquals(DeliveryState.EXPIRED, DeliveryState.valueOf("EXPIRED"));
        assertEquals(DeliveryState.NOT_DURABLE, DeliveryState.valueOf("NOT_DURABLE"));
    }
}
