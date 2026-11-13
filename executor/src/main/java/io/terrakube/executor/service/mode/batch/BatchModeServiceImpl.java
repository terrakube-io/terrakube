package io.terrakube.executor.service.mode.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.configuration.ExecutorFlagsProperties;
import io.terrakube.executor.configuration.security.EncryptionService;
import io.terrakube.executor.service.executor.ExecutorJob;
import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.shutdown.ShutdownServiceImpl;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
@AllArgsConstructor
@NoArgsConstructor
public class BatchModeServiceImpl implements ApplicationListener<ContextRefreshedEvent> {

    @Autowired
    ExecutorJob executorJob;

    @Autowired
    ExecutorFlagsProperties executorFlagsProperties;

    @Autowired
    EncryptionService encryptionService;

    @Autowired
    ShutdownServiceImpl shutdownService;

    @Override
    public void onApplicationEvent(ContextRefreshedEvent contextRefreshedEvent) {
        log.info("Ephemeral mode is enable: {}", executorFlagsProperties.isEphemeral());
        if (executorFlagsProperties.isEphemeral()) {
            try {
                log.info("Running in ephemeral mode....");
                String encryptedBatchJob = new String(Base64.getDecoder().decode(executorFlagsProperties.getEphemeralJobData()), StandardCharsets.UTF_8);
                String batchJob = encryptionService.decrypt(encryptedBatchJob);
                TerraformJob terraformJob = new ObjectMapper().readValue(batchJob, TerraformJob.class);
                log.info("Creating ephemeral job....");
                executorJob.createJob(terraformJob);
            } catch (Exception exception) {
                log.error("Failed to process ephemeral batch job: {}. EphemeralJobData is not encrypted or failed decryption; API and executor versions must match.",
                        exception.getMessage());
                // Deliberately exit with code 1 (Job Failed) rather than closing the context from
                // inside ContextRefreshedEvent, which causes subsequent listeners to fail with IllegalStateException.
                shutdownService.shutdownApplication(1);
            }
        }
    }
}
