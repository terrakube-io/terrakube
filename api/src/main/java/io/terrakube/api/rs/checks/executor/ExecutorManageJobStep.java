package io.terrakube.api.rs.checks.executor;

import com.yahoo.elide.annotation.SecurityCheck;
import com.yahoo.elide.core.security.ChangeSpec;
import com.yahoo.elide.core.security.RequestScope;
import com.yahoo.elide.core.security.checks.OperationCheck;
import io.terrakube.api.rs.job.step.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.Map;
import java.util.Optional;

@Slf4j
@SecurityCheck(ExecutorManageJobStep.RULE)
public class ExecutorManageJobStep extends OperationCheck<Step> {

    public static final String RULE = "executor manage job step";

    @Override
    public boolean ok(Step step, RequestScope requestScope, Optional<ChangeSpec> changeSpec) {
        if (step == null || step.getId() == null) {
            return false;
        }

        if (requestScope.getUser() == null || !(requestScope.getUser().getPrincipal() instanceof JwtAuthenticationToken jwt)) {
            return false;
        }

        Map<String, Object> claims = jwt.getTokenAttributes();
        if (!"TerrakubeInternal".equals(claims.get("iss"))) {
            return false;
        }

        Object workspaceId = claims.get("workspaceId");
        if (workspaceId != null && step.getJob() != null && step.getJob().getWorkspace() != null) {
            if (!step.getJob().getWorkspace().getId().toString().equals(String.valueOf(workspaceId))) {
                return false;
            }
        }

        Object jobId = claims.get("jobId");
        if (jobId != null && step.getJob() != null) {
            if (!String.valueOf(step.getJob().getId()).equals(String.valueOf(jobId))) {
                return false;
            }
        }

        Object stepId = claims.get("stepId");
        if (stepId == null || !step.getId().toString().equals(String.valueOf(stepId))) {
            return false;
        }

        log.debug("ExecutorManageJobStep granted for step {}", step.getId());
        return true;
    }
}
