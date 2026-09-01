package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.service.logs.ProcessLogs;
import io.terrakube.executor.service.terraform.structured.StructuredSnapshot;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultStructuredSnapshotPersisterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private StructuredSnapshot planSnapshot() {
        return StructuredSnapshot.copyOf("o", "1", "step-1", StructuredSnapshot.Phase.PLAN, 1L, false,
                List.of(Map.of("address", "aws_s3_bucket.a", "action", "create")), List.of(), mapper);
    }

    @Test
    void persistDoesGetMergePostThenSseAndReportsSuccess() {
        JobContextService ctx = mock(JobContextService.class);
        when(ctx.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        when(ctx.saveContextChecked(eq("o"), eq("1"), any())).thenReturn(true);
        PlanStructuredOutputService plan = mock(PlanStructuredOutputService.class);
        Map<String, Object> merged = new HashMap<>();
        merged.put("planStructuredOutput", Map.of());
        when(plan.updateContext(any(), eq("step-1"), any(), any())).thenReturn(merged);
        ApplyStructuredOutputService apply = mock(ApplyStructuredOutputService.class);
        ProcessLogs logs = mock(ProcessLogs.class);

        DefaultStructuredSnapshotPersister persister =
                new DefaultStructuredSnapshotPersister(ctx, plan, apply, logs, mapper);

        boolean ok = persister.persist(planSnapshot());

        assertTrue(ok);
        verify(ctx).saveContextChecked(eq("o"), eq("1"), eq(merged));
        verify(logs).sendStructuredUpdate(eq(1), eq("step-1"), contains("\"phase\":\"plan\""));
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) merged.get("structuredOutputStatus");
        assertEquals("PERSISTED", status.get("state"));
        assertEquals("plan", status.get("phase"));
    }

    @Test
    void finalEmptyPlanSnapshotWritesTheNoChangePlanMarker() {
        JobContextService ctx = mock(JobContextService.class);
        when(ctx.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        when(ctx.saveContextChecked(eq("o"), eq("1"), any())).thenReturn(true);
        PlanStructuredOutputService plan = mock(PlanStructuredOutputService.class);
        Map<String, Object> merged = new HashMap<>();
        when(plan.updateContext(any(), eq("step-1"), any(), any())).thenReturn(merged);
        DefaultStructuredSnapshotPersister persister = new DefaultStructuredSnapshotPersister(
                ctx, plan, mock(ApplyStructuredOutputService.class), mock(ProcessLogs.class), mapper);

        StructuredSnapshot emptyFinalPlan = StructuredSnapshot.copyOf("o", "1", "step-1",
                StructuredSnapshot.Phase.PLAN, 2L, true, List.of(), List.of(), mapper);
        assertTrue(persister.persist(emptyFinalPlan));

        @SuppressWarnings("unchecked")
        Map<String, Object> marker = (Map<String, Object>) merged.get("noChangePlan");
        assertEquals("step-1", marker.get("planStepId"));
    }

    @Test
    void finalNonEmptyPlanSnapshotClearsAnyStaleNoChangePlanMarker() {
        JobContextService ctx = mock(JobContextService.class);
        Map<String, Object> merged = new HashMap<>();
        merged.put("noChangePlan", Map.of("planStepId", "step-1"));
        when(ctx.getCurrentContext("o", "1")).thenReturn(new HashMap<>());
        when(ctx.saveContextChecked(eq("o"), eq("1"), any())).thenReturn(true);
        PlanStructuredOutputService plan = mock(PlanStructuredOutputService.class);
        when(plan.updateContext(any(), eq("step-1"), any(), any())).thenReturn(merged);
        DefaultStructuredSnapshotPersister persister = new DefaultStructuredSnapshotPersister(
                ctx, plan, mock(ApplyStructuredOutputService.class), mock(ProcessLogs.class), mapper);

        StructuredSnapshot nonEmptyFinalPlan = StructuredSnapshot.copyOf("o", "1", "step-1",
                StructuredSnapshot.Phase.PLAN, 3L, true,
                List.of(Map.of("address", "aws_s3_bucket.a", "action", "create")), List.of(), mapper);
        assertTrue(persister.persist(nonEmptyFinalPlan));

        assertFalse(merged.containsKey("noChangePlan"));
    }

    @Test
    void persistReturnsFalseWhenSaveFailsAndDoesNotPushSse() {
        JobContextService ctx = mock(JobContextService.class);
        when(ctx.getCurrentContext(any(), any())).thenReturn(new HashMap<>());
        when(ctx.saveContextChecked(any(), any(), any())).thenReturn(false);
        PlanStructuredOutputService plan = mock(PlanStructuredOutputService.class);
        when(plan.updateContext(any(), any(), any(), any())).thenReturn(new HashMap<>());
        ApplyStructuredOutputService apply = mock(ApplyStructuredOutputService.class);
        ProcessLogs logs = mock(ProcessLogs.class);

        DefaultStructuredSnapshotPersister persister =
                new DefaultStructuredSnapshotPersister(ctx, plan, apply, logs, mapper);

        assertFalse(persister.persist(planSnapshot()));
        verify(logs, never()).sendStructuredUpdate(anyInt(), any(), any());
    }

    @Test
    void persistNeverThrowsWhenContextReadBlowsUp() {
        JobContextService ctx = mock(JobContextService.class);
        when(ctx.getCurrentContext(any(), any())).thenThrow(new RuntimeException("boom"));

        DefaultStructuredSnapshotPersister persister = new DefaultStructuredSnapshotPersister(
                ctx, mock(PlanStructuredOutputService.class), mock(ApplyStructuredOutputService.class),
                mock(ProcessLogs.class), mapper);

        assertFalse(persister.persist(planSnapshot()));
    }
}
