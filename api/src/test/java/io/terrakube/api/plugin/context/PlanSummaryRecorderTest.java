package io.terrakube.api.plugin.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.repository.JobRepository;

class PlanSummaryRecorderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JobRepository jobRepository;
    private PlanSummaryRecorder recorder;

    @BeforeEach
    void setUp() {
        jobRepository = mock(JobRepository.class);
        recorder = new PlanSummaryRecorder(jobRepository);
    }

    private static JsonNode finalPlanPatch(String changes, String diagnostics) throws Exception {
        return MAPPER.readTree("""
                {"revision": 1, "set": {
                  "planStructuredOutput/step-1": %s,
                  "jobDiagnostics/step-1": %s,
                  "structuredOutputStatus": {"state": "PERSISTED", "stepId": "step-1", "phase": "plan", "finalSnapshot": true}
                }}
                """.formatted(changes, diagnostics));
    }

    private static String resource(String action) {
        return "{\"address\": \"aws_a.x\", \"action\": \"" + action + "\"}";
    }

    @Test
    void countsEachActionTheWayTerraformPrintsIt() throws Exception {
        JsonNode patch = finalPlanPatch("[" + String.join(",", resource("create"), resource("create"), resource("update"),
                resource("delete")) + "]", "[]");

        assertThat(PlanSummaryRecorder.countsOf(patch)).isEqualTo(new PlanSummaryRecorder.Counts(2, 1, 1));
    }

    @Test
    void aReplacementIsOneAddAndOneDestroy() throws Exception {
        assertThat(PlanSummaryRecorder.countsOf(finalPlanPatch("[" + resource("replace") + "]", "[]")))
                .isEqualTo(new PlanSummaryRecorder.Counts(1, 0, 1));
    }

    @Test
    void importsReadsEphemeralsNoOpsAndOutputChangesAreNotCounted() throws Exception {
        String changes = "[" + String.join(",", resource("import"), resource("read"), resource("ephemeral"),
                resource("no-op"), resource("unknown"),
                "{\"address\": \"id\", \"action\": \"create\", \"isOutputChange\": true}") + "]";

        assertThat(PlanSummaryRecorder.countsOf(finalPlanPatch(changes, "[]")))
                .isEqualTo(new PlanSummaryRecorder.Counts(0, 0, 0));
    }

    @Test
    void aPlanWithAnErrorDiagnosticOrAnErroredResourceHasNoSummary() throws Exception {
        assertThat(PlanSummaryRecorder.countsOf(finalPlanPatch("[" + resource("create") + "]",
                "[{\"severity\": \"error\"}]"))).isNull();
        assertThat(PlanSummaryRecorder.countsOf(finalPlanPatch(
                "[{\"address\": \"a\", \"action\": \"create\", \"status\": \"errored\"}]", "[]"))).isNull();
        // A warning does not stop a plan from having a summary.
        assertThat(PlanSummaryRecorder.countsOf(finalPlanPatch("[" + resource("create") + "]",
                "[{\"severity\": \"warning\"}]"))).isEqualTo(new PlanSummaryRecorder.Counts(1, 0, 0));
    }

    @Test
    void onlyTheFinalSnapshotOfAPlanCounts() throws Exception {
        JsonNode live = MAPPER.readTree("""
                {"revision": 1, "set": {
                  "planStructuredOutput/step-1": [%s],
                  "structuredOutputStatus": {"state": "PERSISTED", "stepId": "step-1", "phase": "plan", "finalSnapshot": false}
                }}
                """.formatted(resource("create")));
        JsonNode apply = MAPPER.readTree("""
                {"revision": 1, "set": {
                  "planStructuredOutput/step-2": [%s],
                  "structuredOutputStatus": {"state": "PERSISTED", "stepId": "step-2", "phase": "apply", "finalSnapshot": true}
                }}
                """.formatted(resource("create")));
        JsonNode unrelated = MAPPER.readTree("{\"revision\": 1, \"set\": {\"terrakubeUI/s1\": \"ui\"}}");

        assertThat(PlanSummaryRecorder.countsOf(live)).isNull();
        assertThat(PlanSummaryRecorder.countsOf(apply)).isNull();
        assertThat(PlanSummaryRecorder.countsOf(unrelated)).isNull();
        assertThat(PlanSummaryRecorder.countsOf(null)).isNull();
    }

    @Test
    void aSummaryIsStoredOnTheJobAndNothingElse() throws Exception {
        boolean recorded = recorder.recordFromPatch(7, finalPlanPatch("[" + resource("update") + "]", "[]"));

        assertThat(recorded).isTrue();
        verify(jobRepository).updatePlanSummary(7, 0, 1, 0);
        // Whether the counts are a drift check is decided when the run has finished well, not while it can still fail.
        verifyNoMoreInteractions(jobRepository);
    }

    @Test
    void nothingIsStoredForAPatchThatIsNotAFinalPlan() throws Exception {
        boolean recorded = recorder.recordFromPatch(7, MAPPER.readTree("{\"revision\": 1, \"set\": {\"terrakubeUI/s1\": \"ui\"}}"));

        assertThat(recorded).isFalse();
        verify(jobRepository, never()).updatePlanSummary(anyInt(), anyInt(), anyInt(), anyInt());
    }
}
