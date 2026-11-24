package io.terrakube.api.plugin.context;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.terrakube.api.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Records what a plan adds, changes and destroys on the job, from the final plan snapshot the executor sends to the
 * context. Done here, not by the executor updating the job, because every update of a job through the API wakes the
 * scheduler again and saves the workspace, and a summary is not worth either. It also works for an executor of any
 * version, since it only reads what the executor has always sent.
 *
 * <p>Counted the way Terraform prints it: a replacement is one add and one destroy; imports, reads, ephemeral
 * resources and output changes are not counted. A plan with an error has no summary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlanSummaryRecorder {

    public static final String PLAN_KEY = "planStructuredOutput/";
    static final String DIAGNOSTICS_KEY = "jobDiagnostics/";

    public record Counts(int add, int change, int destroy) {
    }

    private final JobRepository jobRepository;

    /**
     * @param patch the body of a context patch: {@code {"revision": n, "set": {path: value}}}
     * @return whether the patch was the final snapshot of a plan and a summary was recorded
     */
    public boolean recordFromPatch(int jobId, JsonNode patch) {
        Counts counts = countsOf(patch);
        if (counts == null) {
            return false;
        }
        // Only the counts. Whether they are a drift check is decided when the run has finished well, not while it can
        // still fail, so a plan that was cut short never counts.
        jobRepository.updatePlanSummary(jobId, counts.add(), counts.change(), counts.destroy());
        return true;
    }

    /** Null when the patch is not the final snapshot of a plan, or the plan has an error. */
    static Counts countsOf(JsonNode patch) {
        JsonNode set = patch == null ? null : patch.path("set");
        if (set == null || !set.isObject()) {
            return null;
        }
        JsonNode status = set.path("structuredOutputStatus");
        if (!status.path("finalSnapshot").asBoolean(false) || !"plan".equals(status.path("phase").asText())) {
            return null;
        }
        String stepId = status.path("stepId").asText(null);
        if (stepId == null || stepId.isBlank()) {
            return null;
        }
        JsonNode changes = set.path(PLAN_KEY + stepId);
        if (!changes.isArray() || hasErrors(changes, set.path(DIAGNOSTICS_KEY + stepId))) {
            return null;
        }

        int add = 0;
        int change = 0;
        int destroy = 0;
        for (JsonNode entry : changes) {
            if (entry.path("isOutputChange").asBoolean(false)) {
                continue;
            }
            switch (entry.path("action").asText("")) {
                case "create" -> add++;
                case "update" -> change++;
                case "delete" -> destroy++;
                case "replace" -> {
                    add++;
                    destroy++;
                }
                default -> {
                }
            }
        }
        return new Counts(add, change, destroy);
    }

    private static boolean hasErrors(JsonNode changes, JsonNode diagnostics) {
        if (diagnostics.isArray()) {
            for (JsonNode diagnostic : diagnostics) {
                if ("error".equals(diagnostic.path("severity").asText())) {
                    return true;
                }
            }
        }
        for (JsonNode entry : changes) {
            if ("errored".equals(entry.path("status").asText())) {
                return true;
            }
        }
        return false;
    }
}
