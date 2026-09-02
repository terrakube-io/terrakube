package io.terrakube.api.plugin.logs;

import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.plugin.streaming.StreamingService;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.step.Step;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads a step's console output as plain text - live logs while the step is still streaming,
 * the persisted object afterwards - with terminal colour codes stripped. Shared by every caller
 * that needs the human-readable run text rather than the raw log stream (PR/MR comments,
 * failure-notification summaries, ...).
 */
@Slf4j
@Service
public class StepOutputReader {

    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "[\\u001b\\u009b][\\[()#;?]*(?:\\d{1,4}(?:;\\d{1,4})*)?[0-9A-ORZcf-nq-uy=><~]");

    private final StorageTypeService storageTypeService;
    private final StreamingService streamingService;

    public StepOutputReader(StorageTypeService storageTypeService, StreamingService streamingService) {
        this.storageTypeService = storageTypeService;
        this.streamingService = streamingService;
    }

    /**
     * ANSI-stripped console text for one step, or {@code null} when there is nothing to show or
     * the lookup fails - reading run output is always a best-effort side concern and must never
     * propagate an exception to its caller.
     */
    public String read(Job job, Step step) {
        try {
            String stepId = step.getId().toString();
            String liveLogs = streamingService.getCurrentLogs(stepId, "");
            if (liveLogs != null && !liveLogs.isEmpty()) {
                return stripAnsi(liveLogs);
            }
            byte[] storedOutput = storageTypeService.getStepOutput(
                    job.getOrganization().getId().toString(), String.valueOf(job.getId()), stepId);
            if (storedOutput == null || storedOutput.length == 0) {
                return null;
            }
            return stripAnsi(new String(storedOutput, StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.error("Error fetching step output for job {}: {}", job.getId(), e.getMessage());
            return null;
        }
    }

    public String stripAnsi(String text) {
        return text == null ? null : ANSI_PATTERN.matcher(text).replaceAll("");
    }
}
