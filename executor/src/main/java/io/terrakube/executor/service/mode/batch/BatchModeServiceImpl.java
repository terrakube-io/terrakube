package io.terrakube.executor.service.mode.batch;

import io.terrakube.executor.service.hardened.HardenedRunnerGuard;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import io.terrakube.executor.service.status.UpdateJobStatus;
import io.terrakube.executor.service.workspace.security.RunnerTokenHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Base64;

@Slf4j
@Service
public class BatchModeServiceImpl implements ApplicationListener<ContextRefreshedEvent> {

    @Autowired
    ExecutorJob executorJob;

    @Autowired
    ExecutorFlagsProperties executorFlagsProperties;

    @Autowired
    HardenedRunnerGuard hardenedRunnerGuard;

    @Autowired
    UpdateJobStatus updateJobStatus;

    @Autowired
    ShutdownServiceImpl shutdownService;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        log.info("Ephemeral mode is enable: {}", executorFlagsProperties.isEphemeral());
        if (executorFlagsProperties.isEphemeral())
            try {
                log.info("Running in ephemeral mode....");
                String batchJob = new String(Base64.getDecoder().decode(executorFlagsProperties.getEphemeralJobData()), StandardCharsets.UTF_8);
                TerraformJob terraformJob = new ObjectMapper().readValue(batchJob, TerraformJob.class);
                if (!admit(terraformJob)) {
                    shutdownService.shutdownApplication();
                    return;
                }
                log.info("Creating ephemeral job....");
                executorJob.createJob(terraformJob);
            } catch (IOException exception) {
                log.error(exception.getMessage());
            }
    }

    boolean admit(TerraformJob terraformJob) {
        if (!executorFlagsProperties.isHardened()) {
            if (HardenedRunnerGuard.isHardenedToken(terraformJob.getRunnerToken())) {
                log.error("Refusing a hardened job on a runner that is not in hardened mode");
                return false;
            }
            return true;
        }
        RunnerTokenHolder.set(terraformJob.getRunnerToken());
        List<String> violations = hardenedRunnerGuard.violations(terraformJob);
        if (violations.isEmpty()) {
            return true;
        }
        String message = "Hardened runner refused to execute: " + String.join("; ", violations);
        log.error(message);
        if (RunnerTokenHolder.isSet()) {
            try {
                updateJobStatus.setCompletedStatus(false, false, 1, terraformJob, "", message, null, null);
            } catch (Exception e) {
                log.error("Unable to report the refusal to the API: {}", e.getMessage());
            }
        }
        return false;
    }
}
