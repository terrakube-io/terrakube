package io.terrakube.api.rs.checks.job;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.job.Job;

import java.util.Optional;

/**
 * Lets users change {@code overrideBranch} and {@code commitId} of an existing job, unless its workspace
 * restricts apply to the configured branch. With that setting on, a later {@code PATCH} would otherwise move a job
 * that passed the check at creation onto another branch or commit. Services and the executor are allowed by the
 * other terms of the expression on the field.
 */
@SecurityCheck(JobBranchFieldsEditable.RULE)
public class JobBranchFieldsEditable extends OperationCheck<Job> {

    public static final String RULE = "job branch fields editable";

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> optional) {
        return job.getWorkspace() == null || !job.getWorkspace().isRestrictApplyToConfiguredBranch();
    }
}
