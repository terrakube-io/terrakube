package io.terrakube.api.rs.hooks.job;

import com.yahoo.elide.annotation.LifeCycleHookBinding.Operation;
import com.yahoo.elide.annotation.LifeCycleHookBinding.TransactionPhase;
import com.yahoo.elide.core.exceptions.BadRequestException;
import com.yahoo.elide.core.lifecycle.LifeCycleHook;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.rs.job.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** Resolve a workspace-owned upload without exposing arbitrary source overrides to callers. */
@Component
@RequiredArgsConstructor
public class JobConfigurationHook implements LifeCycleHook<Job> {
    private final ContentRepository contents;

    @Value("${io.terrakube.hostname}")
    private String hostname;

    @Override
    public void execute(Operation operation, TransactionPhase phase, Job job, RequestScope scope,
                        Optional<ChangeSpec> changes) {
        if (operation != Operation.CREATE || phase != TransactionPhase.PREFLUSH || job.getConfigurationVersion() == null) {
            return;
        }
        var content = contents.findById(job.getConfigurationVersion()).orElseThrow(
                () -> new BadRequestException("Configuration must be uploaded to this workspace before creating a job"));
        if (job.getWorkspace() == null || content.getWorkspace() == null
                || !content.getWorkspace().getId().equals(job.getWorkspace().getId())
                || !"uploaded".equals(content.getStatus())) {
            throw new BadRequestException("Configuration must be uploaded to this workspace before creating a job");
        }
        if (job.getCommitId() == null || !job.getCommitId().matches("[a-fA-F0-9]{40}")) {
            throw new BadRequestException("Uploaded configurations require a full commit SHA");
        }
        job.setOverrideSource("https://" + hostname + "/remote/tfe/v2/configuration-versions/"
                + content.getId() + "/terraformContent.tar.gz");
        job.setOverrideBranch("remote-content");
        job.setConfigurationVersion(null);
    }
}
