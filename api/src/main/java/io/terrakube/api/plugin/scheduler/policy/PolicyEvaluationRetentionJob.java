package io.terrakube.api.plugin.scheduler.policy;

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

@Slf4j
@Component
@DisallowConcurrentExecution
public class PolicyEvaluationRetentionJob implements Job {

    private final PolicyEvaluationRepository policyEvaluationRepository;
    private final PolicyOverrideRepository policyOverrideRepository;
    private final long retentionMillis;

    public PolicyEvaluationRetentionJob(
            PolicyEvaluationRepository policyEvaluationRepository,
            PolicyOverrideRepository policyOverrideRepository,
            @Value("${io.terrakube.policy.evaluation.retentionDays:90}") long retentionDays) {
        this.policyEvaluationRepository = policyEvaluationRepository;
        this.policyOverrideRepository = policyOverrideRepository;
        this.retentionMillis = retentionDays * 24 * 60 * 60 * 1000L;
    }

    @Override
    public void execute(JobExecutionContext context) throws JobExecutionException {
        Date cutoff = new Date(System.currentTimeMillis() - retentionMillis);
        log.info("Starting PolicyEvaluationRetentionJob sweep for records older than: {}", cutoff);
        try {
            int overridesDeleted = policyOverrideRepository.deleteByCreatedDateBefore(cutoff);
            int evaluationsDeleted = policyEvaluationRepository.deleteByCreatedDateBefore(cutoff);
            log.info("Policy evaluation retention sweep completed: pruned {} overrides and {} evaluations",
                    overridesDeleted, evaluationsDeleted);
        } catch (Exception e) {
            log.error("Failed executing policy evaluation retention sweep: {}", e.getMessage(), e);
            throw new JobExecutionException(e);
        }
    }
}
