package io.terrakube.api.plugin.policy;

import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyEvaluationStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Decides whether a job that waits for approval may be approved with respect to its policy evaluations.
 * <ul>
 *   <li>A hard-mandatory violation can never be approved. The plan normally stops before it reaches
 *   approval, this keeps the rule true whatever path got it there.</li>
 *   <li>A soft-mandatory violation has to be overridden first (see {@code PolicyOverride}), by a member of
 *   the override team of the policy set.</li>
 *   <li>Shadow and advisory results never block.</li>
 * </ul>
 * One rule for every way of approving a job, so a client cannot get around what the UI shows.
 */
@Service
@RequiredArgsConstructor
public class PolicyApprovalGuard {

    private final PolicyEvaluationRepository policyEvaluationRepository;

    /** The reason the job cannot be approved yet, or empty when it can. */
    public Optional<String> blockingReason(Job job) {
        if (job == null) {
            return Optional.empty();
        }
        return reasonFor(job.getId(), policyEvaluationRepository.findByJob(job));
    }

    static Optional<String> reasonFor(int jobId, List<PolicyEvaluation> evaluations) {
        if (evaluations == null) {
            return Optional.empty();
        }
        long hard = evaluations.stream().filter(evaluation -> evaluation.getHardMandatoryViolations() > 0).count();
        if (hard > 0) {
            return Optional.of(String.format(
                    "Job %d cannot be approved: %s hard-mandatory violations, which cannot be overridden.",
                    jobId, evaluationsWith(hard)));
        }
        // The policy engine itself failed (it could not run, or reported a status that is not known): no
        // violation was counted, but nothing was checked either. A soft-mandatory violation is also
        // stored as FAILED, but with its violations counted, and it is released by an override.
        long failedToRun = evaluations.stream().filter(PolicyApprovalGuard::policyEngineFailed).count();
        if (failedToRun > 0) {
            return Optional.of(String.format(
                    "Job %d cannot be approved: %s not complete, the policy engine failed.",
                    jobId, evaluationsThat(failedToRun)));
        }
        long unresolvedSoft = evaluations.stream()
                .filter(evaluation -> evaluation.getSoftMandatoryViolations() > 0 && evaluation.getOverride() == null)
                .count();
        if (unresolvedSoft > 0) {
            return Optional.of(String.format(
                    "Job %d cannot be approved: %s soft-mandatory violations that have not been overridden.",
                    jobId, evaluationsWith(unresolvedSoft)));
        }
        return Optional.empty();
    }

    private static boolean policyEngineFailed(PolicyEvaluation evaluation) {
        return evaluation.getStatus() == PolicyEvaluationStatus.FAILED
                && evaluation.getHardMandatoryViolations() == 0
                && evaluation.getSoftMandatoryViolations() == 0;
    }

    private static String evaluationsWith(long count) {
        return count == 1 ? "1 policy evaluation has" : count + " policy evaluations have";
    }

    private static String evaluationsThat(long count) {
        return count == 1 ? "1 policy evaluation is" : count + " policy evaluations are";
    }
}
