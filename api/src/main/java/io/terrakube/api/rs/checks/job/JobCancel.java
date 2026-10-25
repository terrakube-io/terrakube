package io.terrakube.api.rs.checks.job;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;

import java.util.Optional;

@SecurityCheck(JobCancel.RULE)
public class JobCancel extends OperationCheck<Job> {
    public static final String RULE = "job cancel";

    @Override
    public boolean ok(Job job, RequestScope requestScope, Optional<ChangeSpec> maybeChange) {
        return maybeChange
                .filter(change -> "status".equals(change.getFieldName()))
                .filter(change -> JobStatus.cancelled.equals(change.getModified()))
                .isPresent();
    }
}
