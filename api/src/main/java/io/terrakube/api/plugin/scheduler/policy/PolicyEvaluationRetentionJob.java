package io.terrakube.api.plugin.scheduler.policy;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.repository.PolicyOverrideRepository;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;

@Slf4j
@Component
@DisallowConcurrentExecution
public class PolicyEvaluationRetentionJob implements Job {

    private final PolicyEvaluationRepository policyEvaluationRepository;
    private final PolicyOverrideRepository policyOverrideRepository;
    private final StorageTypeService storageTypeService;
    private final long retentionMillis;

    public PolicyEvaluationRetentionJob(
            PolicyEvaluationRepository policyEvaluationRepository,
            PolicyOverrideRepository policyOverrideRepository,
            StorageTypeService storageTypeService,
            @Value("${io.terrakube.policy.evaluation.retentionDays:90}") long retentionDays) {
        this.policyEvaluationRepository = policyEvaluationRepository;
        this.policyOverrideRepository = policyOverrideRepository;
        this.storageTypeService = storageTypeService;
        this.retentionMillis = retentionDays * 24 * 60 * 60 * 1000L;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Date cutoff = new Date(System.currentTimeMillis() - retentionMillis);
        log.info("Starting PolicyEvaluationRetentionJob sweep for records older than: {}", cutoff);
        try {
            int storageDeleted = 0;
            if (storageTypeService != null) {
                List<String> storageUris = policyEvaluationRepository.findStorageUrisByCreatedDateBefore(cutoff);
                log.info("Found {} policy evaluation storage object(s) to prune older than {}", storageUris.size(), cutoff);
                for (String uri : storageUris) {
                    if (uri != null && !uri.isBlank()) {
                        try {
                            storageTypeService.deletePolicyEvaluation(uri);
                            storageDeleted++;
                        } catch (Exception e) {
                            log.warn("Failed to delete policy evaluation storage object {}: {}", uri, e.getMessage());
                        }
                    }
                }
            }

            int overridesDeleted = policyOverrideRepository.deleteByCreatedDateBefore(cutoff);
            int evaluationsDeleted = policyEvaluationRepository.deleteByCreatedDateBefore(cutoff);
            log.info("Policy evaluation retention sweep completed: pruned {} overrides, {} evaluations, and {} storage object(s)",
                    overridesDeleted, evaluationsDeleted, storageDeleted);
        } catch (Exception e) {
            log.error("Failed executing policy evaluation retention sweep: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
