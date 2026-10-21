package io.terrakube.executor.service.terraform.spool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.terraform.ApplyStructuredOutputService;
import io.terrakube.executor.service.terraform.JobContextService;
import io.terrakube.executor.service.terraform.PlanStructuredOutputService;
import io.terrakube.terraform.TerraformClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class StructuredOutputSpoolFallbackTest {

    private JobContextService jobContextService;
    private TerraformState terraformState;
    private ContextSpoolService contextSpoolService;
    private PlanStructuredOutputService planService;
    private ApplyStructuredOutputService applyService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        jobContextService = mock(JobContextService.class);
        terraformState = mock(TerraformState.class);
        objectMapper = new ObjectMapper();
        contextSpoolService = new ContextSpoolService(terraformState, objectMapper);

        planService = new PlanStructuredOutputService(
                jobContextService, objectMapper, new TerraformClient(), null, null, contextSpoolService);

        applyService = new ApplyStructuredOutputService(
                jobContextService, objectMapper, null, null, contextSpoolService);
    }

    @Test
    void planFallbackToSpoolOnApiFailure() {
        when(jobContextService.patchContext(eq("org1"), eq("job1"), anyLong(), any()))
                .thenReturn(JobContextService.PatchResult.FAILED);
        when(terraformState.spoolContextPatch(eq("org1"), eq("job1"), eq("step1"), eq("plan"), any()))
                .thenReturn("context-spool/org1/job1/step1-plan.json.gz");

        String state = planService.publishFinalPlanSnapshotSynchronous("org1", "job1", "step1", List.of(), List.of());

        assertEquals("SPOOLED", state);
        verify(jobContextService, times(3)).patchContext(eq("org1"), eq("job1"), anyLong(), any());
        verify(terraformState).spoolContextPatch(eq("org1"), eq("job1"), eq("step1"), eq("plan"), any());
    }

    @Test
    void planNotDurableWhenBothApiAndStorageFail() {
        when(jobContextService.patchContext(eq("org1"), eq("job1"), anyLong(), any()))
                .thenReturn(JobContextService.PatchResult.FAILED);
        when(terraformState.spoolContextPatch(eq("org1"), eq("job1"), eq("step1"), eq("plan"), any()))
                .thenReturn(null);

        String state = planService.publishFinalPlanSnapshotSynchronous("org1", "job1", "step1", List.of(), List.of());

        assertEquals("NOT_DURABLE", state);
    }

    @Test
    void applyFallbackToSpoolOnApiFailure() {
        when(jobContextService.patchContext(eq("org1"), eq("job1"), anyLong(), any()))
                .thenReturn(JobContextService.PatchResult.FAILED);
        when(terraformState.spoolContextPatch(eq("org1"), eq("job1"), eq("step1"), eq("apply"), any()))
                .thenReturn("context-spool/org1/job1/step1-apply.json.gz");

        String state = applyService.publishFinalApplySynchronous("org1", "job1", "step1", List.of(), List.of());

        assertEquals("SPOOLED", state);
        verify(jobContextService, times(3)).patchContext(eq("org1"), eq("job1"), anyLong(), any());
        verify(terraformState).spoolContextPatch(eq("org1"), eq("job1"), eq("step1"), eq("apply"), any());
    }

    @Test
    void applyNotDurableWhenBothApiAndStorageFail() {
        when(jobContextService.patchContext(eq("org1"), eq("job1"), anyLong(), any()))
                .thenReturn(JobContextService.PatchResult.FAILED);
        when(terraformState.spoolContextPatch(eq("org1"), eq("job1"), eq("step1"), eq("apply"), any()))
                .thenReturn(null);

        String state = applyService.publishFinalApplySynchronous("org1", "job1", "step1", List.of(), List.of());

        assertEquals("NOT_DURABLE", state);
    }
}
