package io.terrakube.api.plugin.policy;

import io.terrakube.api.repository.PolicyEvaluationRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.policy.PolicyEvaluation;
import io.terrakube.api.rs.policy.PolicyEvaluationStatus;
import io.terrakube.api.rs.policy.PolicyOverride;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

class PolicyApprovalGuardTest {

    private static PolicyEvaluation evaluation(int soft, int hard, boolean overridden) {
        PolicyEvaluation evaluation = new PolicyEvaluation();
        evaluation.setSoftMandatoryViolations(soft);
        evaluation.setHardMandatoryViolations(hard);
        if (overridden) {
            evaluation.setOverride(new PolicyOverride());
        }
        return evaluation;
    }

    private static Optional<String> reason(PolicyEvaluation... evaluations) {
        return PolicyApprovalGuard.reasonFor(7, List.of(evaluations));
    }

    @Test
    void aJobWithoutEvaluationsOrWithCleanOnesCanBeApproved() {
        assertTrue(reason().isEmpty());
        assertTrue(reason(evaluation(0, 0, false), evaluation(0, 0, false)).isEmpty());
    }

    @Test
    void aSoftViolationBlocksUntilItIsOverridden() {
        assertTrue(reason(evaluation(2, 0, false)).orElseThrow().contains("soft-mandatory"));
        assertTrue(reason(evaluation(2, 0, true)).isEmpty());
    }

    @Test
    void oneUnresolvedEvaluationBlocksEvenWhenAnotherWasOverridden() {
        assertEquals(true, reason(evaluation(1, 0, true), evaluation(1, 0, false)).isPresent());
    }

    @Test
    void aHardViolationBlocksAndAnOverrideDoesNotHelp() {
        assertTrue(reason(evaluation(0, 1, false)).orElseThrow().contains("hard-mandatory"));
        assertTrue(reason(evaluation(1, 1, true)).orElseThrow().contains("hard-mandatory"));
    }

    @Test
    void shadowAndAdvisoryResultsNeverBlock() {
        PolicyEvaluation evaluation = evaluation(0, 0, false);
        evaluation.setShadowHardViolations(3);
        evaluation.setShadowSoftViolations(3);
        evaluation.setWarningRules(4);

        assertTrue(reason(evaluation).isEmpty());
    }

    @Test
    void aPolicyEngineThatFailedWithoutCountingViolationsBlocks() {
        PolicyEvaluation failed = evaluation(0, 0, false);
        failed.setStatus(PolicyEvaluationStatus.FAILED);

        assertTrue(reason(failed).orElseThrow().contains("policy engine failed"));
    }

    @Test
    void aSoftViolationStoredAsFailedIsStillReleasedByAnOverride() {
        // The executor reports a soft-mandatory result with a status that is stored as FAILED.
        PolicyEvaluation overridden = evaluation(1, 0, true);
        overridden.setStatus(PolicyEvaluationStatus.FAILED);
        PolicyEvaluation open = evaluation(1, 0, false);
        open.setStatus(PolicyEvaluationStatus.FAILED);

        assertTrue(reason(overridden).isEmpty());
        assertTrue(reason(open).orElseThrow().contains("soft-mandatory"));
    }

    @Test
    void passedAndWarningEvaluationsDoNotBlock() {
        PolicyEvaluation passed = evaluation(0, 0, false);
        passed.setStatus(PolicyEvaluationStatus.PASSED);
        PolicyEvaluation warning = evaluation(0, 0, false);
        warning.setStatus(PolicyEvaluationStatus.WARNING);

        assertTrue(reason(passed, warning).isEmpty());
    }

    @Test
    void theMessageUsesTheSingularForOneEvaluationAndThePluralForMore() {
        assertTrue(reason(evaluation(1, 0, false)).orElseThrow().contains("1 policy evaluation has soft-mandatory"));
        assertTrue(reason(evaluation(1, 0, false), evaluation(2, 0, false)).orElseThrow()
                .contains("2 policy evaluations have soft-mandatory"));
        assertTrue(reason(evaluation(0, 1, false)).orElseThrow().contains("1 policy evaluation has hard-mandatory"));
    }

    @Test
    void missingInputDoesNotThrow() {
        assertTrue(PolicyApprovalGuard.reasonFor(7, null).isEmpty());
        assertTrue(new PolicyApprovalGuard(Mockito.mock(PolicyEvaluationRepository.class)).blockingReason(null).isEmpty());
        PolicyEvaluationRepository repository = Mockito.mock(PolicyEvaluationRepository.class);
        Job job = new Job();
        job.setId(7);
        when(repository.findByJob(job)).thenReturn(null);
        assertTrue(new PolicyApprovalGuard(repository).blockingReason(job).isEmpty());
    }

    @Test
    void theMessageNamesTheJob() {
        assertTrue(reason(evaluation(1, 0, false)).orElseThrow().startsWith("Job 7 cannot be approved"));
    }

    @Test
    void theServiceReadsTheEvaluationsOfTheJob() {
        PolicyEvaluationRepository repository = Mockito.mock(PolicyEvaluationRepository.class);
        Job job = new Job();
        job.setId(7);
        when(repository.findByJob(job)).thenReturn(List.of(evaluation(1, 0, false)));

        assertTrue(new PolicyApprovalGuard(repository).blockingReason(job).isPresent());
    }
}
