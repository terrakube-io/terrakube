package io.terrakube.executor.service.terraform.structured;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredSnapshotTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void copyOfDeepCopiesSoLaterMutationDoesNotLeak() {
        List<Map<String, Object>> changes = new ArrayList<>();
        Map<String, Object> row = new HashMap<>();
        row.put("address", "aws_s3_bucket.a");
        changes.add(row);

        StructuredSnapshot snapshot = StructuredSnapshot.copyOf(
                "org", "42", "step-1", StructuredSnapshot.Phase.PLAN, 1L, false, changes, List.of(), mapper);

        row.put("address", "MUTATED");
        changes.add(new HashMap<>());

        assertEquals(1, snapshot.getChanges().size());
        assertEquals("aws_s3_bucket.a", snapshot.getChanges().get(0).get("address"));
        assertEquals(new StructuredSnapshot.Key("42", "step-1", StructuredSnapshot.Phase.PLAN), snapshot.key());
        assertTrue(snapshot.getJobDiagnostics().isEmpty());
    }

    @Test
    void copyOfToleratesNullLists() {
        StructuredSnapshot snapshot = StructuredSnapshot.copyOf(
                "org", "42", "s", StructuredSnapshot.Phase.APPLY, 7L, true, null, null, mapper);

        assertTrue(snapshot.getChanges().isEmpty());
        assertTrue(snapshot.isFinalSnapshot());
        assertEquals(7L, snapshot.getSequence());
    }

    @Test
    void copyOfRejectsUnserializableContent() {
        List<Map<String, Object>> changes = List.of(Map.of("bad", new Object()));
        assertThrows(StructuredSnapshot.SnapshotSerializationException.class, () -> StructuredSnapshot.copyOf(
                "org", "42", "s", StructuredSnapshot.Phase.PLAN, 1L, false, changes, List.of(), mapper));
    }
}
