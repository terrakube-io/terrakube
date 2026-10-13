package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.terraform.JobContextService.PatchResult;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot;
import io.terrakube.terraform.TerraformClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultStructuredSnapshotPersisterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final JobContextService ctx = mock(JobContextService.class);
    private final ProcessLogs logs = mock(ProcessLogs.class);
    private final DefaultStructuredSnapshotPersister persister = new DefaultStructuredSnapshotPersister(ctx,
            new PlanStructuredOutputService(ctx, mapper, mock(TerraformClient.class)),
            new ApplyStructuredOutputService(ctx, mapper), logs, mapper);

    private StructuredSnapshot planSnapshot(long sequence, boolean finalSnapshot, List<Map<String, Object>> changes) {
        return StructuredSnapshot.copyOf("o", "1", "step-1", StructuredSnapshot.Phase.PLAN, sequence, finalSnapshot,
                changes, List.of(), mapper);
    }

    private StructuredSnapshot planSnapshot() {
        return planSnapshot(1L, false, List.of(Map.of("address", "aws_s3_bucket.a", "action", "create")));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> patchedSet(long revision) {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(ctx).patchContext(eq("o"), eq("1"), eq(revision), captor.capture());
        return captor.getValue();
    }

    @Test
    void persistPatchesThePlanEntriesWithTheSequenceAsRevisionThenPushesSse() {
        when(ctx.patchContext(eq("o"), eq("1"), anyLong(), any())).thenReturn(PatchResult.APPLIED);

        assertTrue(persister.persist(planSnapshot()));

        Map<String, Object> set = patchedSet(1L);
        assertEquals(Set.of("planStructuredOutput/step-1", "terrakubeUI/step-1", "jobDiagnostics/step-1",
                "structuredOutputStatus"), set.keySet());
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) set.get("structuredOutputStatus");
        assertEquals("PERSISTED", status.get("state"));
        assertEquals("plan", status.get("phase"));
        verify(ctx, never()).getCurrentContext(any(), any());
        verify(logs).sendStructuredUpdate(eq(1), eq("step-1"), contains("\"phase\":\"plan\""));
    }

    @Test
    void persistPatchesTheApplyEntries() {
        when(ctx.patchContext(eq("o"), eq("1"), anyLong(), any())).thenReturn(PatchResult.APPLIED);
        StructuredSnapshot apply = StructuredSnapshot.copyOf("o", "1", "step-2", StructuredSnapshot.Phase.APPLY, 5L,
                true, List.of(Map.of("address", "aws_s3_bucket.a", "status", "applied")), List.of(), mapper);

        assertTrue(persister.persist(apply));

        assertEquals(Set.of("applyStructuredOutput/step-2", "jobDiagnostics/step-2", "structuredOutputStatus"),
                patchedSet(5L).keySet());
        verify(ctx, never()).getCurrentContext(any(), any());
    }

    @Test
    void finalEmptyPlanSnapshotWritesTheNoChangePlanMarker() {
        when(ctx.patchContext(eq("o"), eq("1"), anyLong(), any())).thenReturn(PatchResult.APPLIED);

        assertTrue(persister.persist(planSnapshot(2L, true, List.of())));

        assertEquals(Map.of("planStepId", "step-1"), patchedSet(2L).get("noChangePlan"));
    }

    @Test
    void finalNonEmptyPlanSnapshotClearsAnyStaleNoChangePlanMarker() {
        when(ctx.patchContext(eq("o"), eq("1"), anyLong(), any())).thenReturn(PatchResult.APPLIED);

        assertTrue(persister.persist(planSnapshot(3L, true, List.of(Map.of("address", "aws_s3_bucket.a", "action", "create")))));

        Map<String, Object> set = patchedSet(3L);
        assertTrue(set.containsKey("noChangePlan"));
        assertNull(set.get("noChangePlan"));
    }

    @Test
    void progressPlanSnapshotLeavesTheNoChangePlanMarkerAlone() {
        when(ctx.patchContext(eq("o"), eq("1"), anyLong(), any())).thenReturn(PatchResult.APPLIED);

        assertTrue(persister.persist(planSnapshot()));

        assertFalse(patchedSet(1L).containsKey("noChangePlan"));
    }

    @Test
    void skippedPatchIsDoneButPushesNoSse() {
        when(ctx.patchContext(any(), any(), anyLong(), any())).thenReturn(PatchResult.SKIPPED);

        assertTrue(persister.persist(planSnapshot()));
        verify(logs, never()).sendStructuredUpdate(anyInt(), any(), any());
    }

    @Test
    void persistReturnsFalseWhenPatchFailsAndDoesNotPushSse() {
        when(ctx.patchContext(any(), any(), anyLong(), any())).thenReturn(PatchResult.FAILED);

        assertFalse(persister.persist(planSnapshot()));
        verify(logs, never()).sendStructuredUpdate(anyInt(), any(), any());
    }

    @Test
    void persistNeverThrowsWhenThePatchBlowsUp() {
        when(ctx.patchContext(any(), any(), anyLong(), any())).thenThrow(new RuntimeException("boom"));

        assertFalse(persister.persist(planSnapshot()));
    }
}
