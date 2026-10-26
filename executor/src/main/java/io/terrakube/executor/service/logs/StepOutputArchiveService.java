package io.terrakube.executor.service.logs;

import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import io.terrakube.executor.service.mode.TerraformJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Attaches a {@link StepOutputPartWriter} to the output buffer of a step, so what the step has printed so
 * far is kept in the object store while it runs.
 */
@Slf4j
@Service
public class StepOutputArchiveService {

    private final TerraformState terraformState;
    private final ExecutorLogsProperties properties;
    private final MeterRegistry meterRegistry;

    public StepOutputArchiveService(TerraformState terraformState, ExecutorLogsProperties properties, MeterRegistry meterRegistry) {
        this.terraformState = terraformState;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        if (properties.isArchiveEnabled() && (properties.getArchivePartBytes() < 1 || properties.getArchiveIntervalSeconds() < 0 || properties.getArchiveMaxParts() < 1)) {
            log.warn("Output archive settings out of range (partBytes={}, intervalSeconds={}, maxParts={}), the minimum allowed is used instead",
                    properties.getArchivePartBytes(), properties.getArchiveIntervalSeconds(), properties.getArchiveMaxParts());
        }
    }

    public void start(StepOutputBuffer buffer, TerraformJob job) {
        if (!properties.isArchiveEnabled() || job == null || job.getOrganizationId() == null
                || job.getJobId() == null || job.getStepId() == null) {
            return;
        }
        String organizationId = job.getOrganizationId();
        String jobId = job.getJobId();
        String stepId = job.getStepId();
        StepOutputPartWriter writer = new StepOutputPartWriter(
                buffer,
                (number, data) -> {
                    if (!terraformState.saveOutputPart(organizationId, jobId, stepId, number, data)) {
                        // The backend keeps no parts, so there is nothing to retry.
                        throw new UnsupportedOperationException("storage backend does not keep output parts");
                    }
                },
                properties.getArchivePartBytes(),
                Duration.ofSeconds(properties.getArchiveIntervalSeconds()),
                properties.getArchiveMaxParts(),
                "job " + jobId + " step " + stepId).withMetrics(meterRegistry);
        buffer.attachArchiver(writer);
        writer.start();
    }
}
