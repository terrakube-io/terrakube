package io.terrakube.api.rs.hooks.job;

import java.util.Optional;

import com.yahoo.elide.annotation.LifeCycleHookBinding;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes the files a plan step stored for its apply step once the job is over, whichever way it
 * ended (applied, failed, cancelled, rejected, no changes). Without it a bundle that is never applied
 * would stay in the bucket until a lifecycle rule expires it. The executor already deletes it after a
 * successful apply; this covers every other ending, and runs again harmlessly after that one.
 *
 * <p>Best effort: a storage problem is logged and must not fail the request that finished the job.
 * Jobs that are ended by the scheduler rather than through the API do not reach this hook, so a
 * lifecycle rule on the {@code plan-artifacts/} prefix remains the backstop.
 */
@AllArgsConstructor
@Slf4j
public class JobArtifactsCleanupHook implements LifeCycleHook<Job> {

    private StorageTypeService storageTypeService;
    private JobRepository jobRepository;

    @Override
    public void execute(LifeCycleHookBinding.Operation operation, LifeCycleHookBinding.TransactionPhase transactionPhase,
                        Job job, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (job.getStatus() == null || !job.getStatus().isTerminal()
                || job.getOrganization() == null || job.getWorkspace() == null) {
            return;
        }
        String location = job.getTerraformPlanArtifacts();
        if (location == null || location.isBlank()) {
            return;
        }
        try {
            storageTypeService.deleteJobArtifacts(job.getOrganization().getId().toString(),
                    job.getWorkspace().getId().toString(), job.getId());
            jobRepository.clearPlanArtifactsLocation(job.getId());
            log.info("Deleted the plan artifacts of job {} (now {})", job.getId(), job.getStatus());
        } catch (RuntimeException e) {
            log.warn("Could not delete the plan artifacts of job {}: {}", job.getId(), e.getMessage());
        }
    }
}
