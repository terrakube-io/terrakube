# Zero-pending-step job reconciliation and queue liveness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a non-terminal job that has no remaining executable step from permanently blocking the shared executor FIFO queue.

**Architecture:** One shared, idempotent routine (`JobReconciliationService`) derives a terminal status from persisted step outcomes and applies it through the existing status-transition/notification paths. The scheduler's zero-pending branches, the 30s reconciliation sweep, and a new protected admin endpoint all call it. A second line of defence changes the executor-admission SQL so a job with steps but no pending step no longer counts as a queue blocker. New Micrometer metrics and structured logs make stuck jobs visible; config flags gate the proactive (sweep) remediation and the admission-query change for a phased rollout.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA (Hibernate), Quartz, Micrometer, Redis (Spring Data Redis), JUnit 5 + Mockito + AssertJ, Testcontainers (PostgreSQL 16). Build: Maven multi-module; the API module is `api/`.

**Spec:** `docs/superpowers/specs/2026-09-02-zero-pending-job-reconciliation-design.md` — read it alongside this plan.

## Global Constraints

- All new production code lives in the `api` Maven module under `api/src/main/java/io/terrakube/api/...`; tests under `api/src/test/java/io/terrakube/api/...`.
- Build/test with the pinned wrapper `~/.local/toolchain/mvnj` (JDK 25 + Maven 3.9.9) — plain `mvn` is not on PATH and system `java` is 8. Run tests from the repo root: `~/.local/toolchain/mvnj -pl api -o -Dtest=<ClassName> test` (single class) or `~/.local/toolchain/mvnj -pl api -o test` (all). Drop `-o` if a dependency needs downloading. Integration tests (`*IntegrationTest`) need Docker running for Testcontainers.
- Do **not** commit any file under `docs/` (repo convention: design/plan docs stay local unless explicitly asked).
- Commit messages: conventional-commit prefix (`feat:`, `fix:`, `test:`, `refactor:`, `docs:`). Do **not** add a `Co-Authored-By` trailer (standing user preference).
- Job status values are the `io.terrakube.api.rs.job.JobStatus` enum. Terminal statuses for queue purposes: `failed, completed, rejected, cancelled, noChanges` (matches `JobRepository.TERMINAL_JOB_STATUSES`). **Do not introduce a `noChanges` transition** — a no-change plan is already recorded as `completed` + `planChanges=false` (see spec §1.2).
- Metric names are dot-delimited in code (repo convention, e.g. `quartz.jobs.executing`); Prometheus renders them with underscores. Use the exact dot names in the Metrics table of the spec §3.9.
- Micrometer counter idiom in this codebase: `meterRegistry.counter("dot.name", "tag", tagValue).increment();`. Gauge idiom: `Gauge.builder("dot.name", source, fn).description("...").register(meterRegistry);` inside a `@PostConstruct`.
- New config properties use `@ConfigurationProperties(prefix = "io.terrakube.api.scheduler.reconciliation")` and must be registered (the module already uses `@ConfigurationPropertiesScan` or explicit `@EnableConfigurationProperties` — check `ServerApplication` / existing `*Properties` classes and follow the same registration).
- Never log credentials, tokens, TCL contents, or variable values in any new code.

---

## File Structure

**Create:**
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/DerivedOutcome.java` — enum of derivation results.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobTerminalStateDeriver.java` — pure `(Job, List<Step>) → DerivedOutcome`.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/ReconciliationProperties.java` — rollout flags.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/ReconciliationResult.java` — service result (disposition + derived outcome + target + evidence).
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationMetrics.java` — thin counter wrapper.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationService.java` — the shared routine.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerQueueMetrics.java` — queue-depth/head gauges.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationController.java` — protected admin endpoint.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationAccessService.java` — `@PreAuthorize` helper.
- Tests mirroring each of the above under `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/`.
- `docs/ops/zero-pending-job-reconciliation.md` — operator runbook + alert rules (NOT committed; delivered to the user).

**Modify:**
- `api/src/main/java/io/terrakube/api/repository/JobRepository.java` — guarded admission queries + queue read methods.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/ScheduleJob.java` — delegate zero-pending branches to the service; delete `completeJob`; select guarded/unguarded dispatch query by flag.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/ExecutorAvailabilityListener.java` — select guarded/unguarded next-job query by flag.
- `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweep.java` — call the service before trigger reconciliation; wrap trigger create/race in counters.
- `api/src/main/resources/application.properties` — documented defaults for the new flags.
- `api/src/test/java/io/terrakube/api/plugin/scheduler/ScheduleJobTest.java` — constructor arg + new/updated cases.
- `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepTest.java` — constructor args + new cases.
- `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepIntegrationTest.java` — zombie-reconciliation case.
- `api/src/test/java/io/terrakube/api/plugin/scheduler/JobDispatchOrderRepositoryIntegrationTest.java` — guard semantics cases.

---

## Task 1: `JobTerminalStateDeriver` — pure terminal-state derivation

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/DerivedOutcome.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobTerminalStateDeriver.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobTerminalStateDeriverTest.java`

**Interfaces:**
- Produces:
  - `enum DerivedOutcome { ALREADY_TERMINAL, FAILED, CANCELLED, REJECTED, COMPLETED, RETAIN_WAITING_APPROVAL, ANOMALY }`
  - `DerivedOutcome JobTerminalStateDeriver.derive(Job job, List<Step> steps)` — pure, no I/O. Precondition (caller-enforced): `steps` contains zero `JobStatus.pending` steps.
  - `Optional<JobStatus> DerivedOutcome.targetStatus()` — `FAILED→failed`, `CANCELLED→cancelled`, `REJECTED→rejected`, `COMPLETED→completed`, others empty.

- [ ] **Step 1: Write `DerivedOutcome`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.JobStatus;

import java.util.Optional;

/** Result of {@link JobTerminalStateDeriver#derive}. Only FAILED/CANCELLED/REJECTED/COMPLETED
 *  carry a target status the service should transition to. */
public enum DerivedOutcome {
    ALREADY_TERMINAL(null),
    FAILED(JobStatus.failed),
    CANCELLED(JobStatus.cancelled),
    REJECTED(JobStatus.rejected),
    COMPLETED(JobStatus.completed),
    RETAIN_WAITING_APPROVAL(null),
    ANOMALY(null);

    private final JobStatus target;

    DerivedOutcome(JobStatus target) {
        this.target = target;
    }

    public Optional<JobStatus> targetStatus() {
        return Optional.ofNullable(target);
    }

    public boolean isTerminalTransition() {
        return target != null;
    }
}
```

- [ ] **Step 2: Write the failing test**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JobTerminalStateDeriverTest {

    private final JobTerminalStateDeriver deriver = new JobTerminalStateDeriver();

    private Job job(JobStatus status) {
        Job j = new Job();
        j.setStatus(status);
        return j;
    }

    private Step step(JobStatus status) {
        Step s = new Step();
        s.setStatus(status);
        return s;
    }

    @Test
    void approvedJobWithAllStepsCompletedDerivesCompleted() {
        DerivedOutcome outcome = deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.completed)));
        assertThat(outcome).isEqualTo(DerivedOutcome.COMPLETED);
        assertThat(outcome.targetStatus()).contains(JobStatus.completed);
    }

    @Test
    void singleCompletedPlanStepWithNoChangesDerivesCompleted() {
        Job j = job(JobStatus.approved);
        j.setPlanChanges(false);
        assertThat(deriver.derive(j, List.of(step(JobStatus.completed)))).isEqualTo(DerivedOutcome.COMPLETED);
    }

    @Test
    void anyFailedStepDerivesFailedEvenWithLaterCompletedSteps() {
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.failed))))
                .isEqualTo(DerivedOutcome.FAILED);
    }

    @Test
    void aCancelledStepDerivesCancelled() {
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.cancelled))))
                .isEqualTo(DerivedOutcome.CANCELLED);
    }

    @Test
    void aRejectedStepOrRejectedJobDerivesRejected() {
        assertThat(deriver.derive(job(JobStatus.approved), List.of(step(JobStatus.rejected))))
                .isEqualTo(DerivedOutcome.REJECTED);
        assertThat(deriver.derive(job(JobStatus.rejected), List.of(step(JobStatus.completed))))
                .isEqualTo(DerivedOutcome.ALREADY_TERMINAL); // rejected is already terminal - rule 1 wins
    }

    @Test
    void alreadyTerminalJobIsLeftAlone() {
        for (JobStatus terminal : List.of(JobStatus.completed, JobStatus.failed, JobStatus.rejected,
                JobStatus.cancelled, JobStatus.noChanges)) {
            assertThat(deriver.derive(job(terminal), List.of(step(JobStatus.completed))))
                    .isEqualTo(DerivedOutcome.ALREADY_TERMINAL);
        }
    }

    @Test
    void waitingApprovalWithZeroPendingStepsIsRetainedNotTransitioned() {
        assertThat(deriver.derive(job(JobStatus.waitingApproval), List.of(step(JobStatus.completed))))
                .isEqualTo(DerivedOutcome.RETAIN_WAITING_APPROVAL);
    }

    @Test
    void noStepsAtAllIsAnAnomaly() {
        assertThat(deriver.derive(job(JobStatus.approved), List.of())).isEqualTo(DerivedOutcome.ANOMALY);
    }

    @Test
    void notExecutedStepsCountAsDoneForCompletion() {
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.notExecuted))))
                .isEqualTo(DerivedOutcome.COMPLETED);
    }

    @Test
    void anUnexpectedStepStatusMixIsAnAnomaly() {
        // e.g. a 'queue' step left behind with no pending steps and status approved
        assertThat(deriver.derive(job(JobStatus.approved),
                List.of(step(JobStatus.completed), step(JobStatus.queue))))
                .isEqualTo(DerivedOutcome.ANOMALY);
    }
}
```

- [ ] **Step 3: Run the test, verify it fails**

Run: `mvn -pl api -Dtest=JobTerminalStateDeriverTest test`
Expected: compile failure / FAIL — `JobTerminalStateDeriver` does not exist.

- [ ] **Step 4: Write `JobTerminalStateDeriver`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Derives the terminal status a job should hold when it has zero pending executable steps,
 * from the persisted step outcomes alone. Pure: no repository or scheduler access.
 *
 * Precedence (see design §3.3). The caller guarantees {@code steps} has no
 * {@link JobStatus#pending} entry before calling.
 */
@Slf4j
@Component
public class JobTerminalStateDeriver {

    private static final Set<JobStatus> TERMINAL_JOB_STATUSES = Set.of(
            JobStatus.completed, JobStatus.failed, JobStatus.rejected,
            JobStatus.cancelled, JobStatus.noChanges);

    // Step statuses that mean "this step is done and did not fail".
    private static final Set<JobStatus> STEP_DONE_OK = Set.of(JobStatus.completed, JobStatus.notExecuted);

    public DerivedOutcome derive(Job job, List<Step> steps) {
        if (TERMINAL_JOB_STATUSES.contains(job.getStatus())) {
            return DerivedOutcome.ALREADY_TERMINAL;
        }
        if (steps.stream().anyMatch(s -> s.getStatus() == JobStatus.failed)) {
            return DerivedOutcome.FAILED;
        }
        if (steps.stream().anyMatch(s -> s.getStatus() == JobStatus.cancelled)) {
            return DerivedOutcome.CANCELLED;
        }
        if (job.getStatus() == JobStatus.rejected
                || steps.stream().anyMatch(s -> s.getStatus() == JobStatus.rejected)) {
            return DerivedOutcome.REJECTED;
        }
        if (job.getStatus() == JobStatus.waitingApproval) {
            return DerivedOutcome.RETAIN_WAITING_APPROVAL;
        }
        if (!steps.isEmpty() && steps.stream().allMatch(s -> STEP_DONE_OK.contains(s.getStatus()))) {
            return DerivedOutcome.COMPLETED;
        }
        log.warn("Job {} has zero pending steps but no derivable terminal state: status={}, steps={}",
                job.getId(), job.getStatus(),
                steps.stream().map(Step::getStatus).toList());
        return DerivedOutcome.ANOMALY;
    }
}
```

- [ ] **Step 5: Run the test, verify it passes**

Run: `mvn -pl api -Dtest=JobTerminalStateDeriverTest test`
Expected: PASS (all cases).

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/DerivedOutcome.java \
        api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobTerminalStateDeriver.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobTerminalStateDeriverTest.java
git commit -m "feat: add pure terminal-state deriver for zero-pending jobs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 2: `ReconciliationProperties` + guarded executor-admission queries

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/ReconciliationProperties.java`
- Modify: `api/src/main/java/io/terrakube/api/repository/JobRepository.java`
- Modify: `api/src/main/resources/application.properties`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/JobDispatchOrderRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `ReconciliationProperties` with getters: `boolean isSweepEnabled()` (default `true`), `boolean isAutoRemediate()` (default `true`), `boolean isAdmissionGuardEnabled()` (default `true`), `int getAnomalyGraceSeconds()` (default `300`).
  - `boolean JobRepository.isJobNextInDispatchOrderExecutable(int candidateJobId)` — the guarded variant of `isJobNextInDispatchOrder`.
  - `Integer JobRepository.findNextDispatchableExecutableJobId()` — the guarded variant of `findNextDispatchableJobId`.
  - Existing `isJobNextInDispatchOrder` / `findNextDispatchableJobId` are unchanged.

- [ ] **Step 1: Write `ReconciliationProperties`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "io.terrakube.api.scheduler.reconciliation")
public class ReconciliationProperties {

    /** Master switch: the 30s sweep calls the reconciliation routine, and the admin endpoint
     *  may apply transitions. Off = sweep does trigger/heartbeat repair only (today's behaviour);
     *  the endpoint GET report still works. Does not affect the scheduler inline path. */
    private boolean sweepEnabled = true;

    /** When sweepEnabled: the sweep applies deterministic completed/failed/cancelled/rejected
     *  transitions. Off = the sweep runs the routine in dry-run (metrics + logs only). */
    private boolean autoRemediate = true;

    /** Use the guarded executor-admission queries (a job with steps but no pending step no
     *  longer blocks the FIFO queue). */
    private boolean admissionGuardEnabled = true;

    /** Age threshold (seconds) past which a non-terminal zero-pending job is flagged stale by
     *  the admin report and the alert rule. */
    private int anomalyGraceSeconds = 300;
}
```

Check how other `*Properties` classes in `api` are registered (e.g. `ModuleRefreshProperties`, `CacheConfigurationProperties`). If they rely on `@Component` + component scan, the `@Component` above is enough. If the module uses `@EnableConfigurationProperties`, add `ReconciliationProperties.class` there instead and drop `@Component`. Match the existing pattern exactly.

- [ ] **Step 2: Write the failing integration test cases**

Add to `JobDispatchOrderRepositoryIntegrationTest` (reuse its existing `@BeforeEach` helpers for creating orgs/workspaces/jobs — inspect the file for the exact helper names, e.g. `newJob(workspace, status)`; if a step-creating helper does not exist, add a private `Step newStep(Job job, int number, JobStatus status)` using an `@Autowired StepRepository`).

```java
@Test
void guardedQuery_zombieApprovedJobWithNoPendingStepDoesNotBlockLaterJob() {
    Workspace wsA = newWorkspace();
    Workspace wsB = newWorkspace();
    Job zombie = newJob(wsA, JobStatus.approved);           // older
    newStep(zombie, 100, JobStatus.completed);              // has a step, none pending
    Job later = newJob(wsB, JobStatus.pending);             // newer, different workspace
    newStep(later, 100, JobStatus.pending);                 // genuine executable work

    assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isTrue();
    // the un-guarded query still (wrongly) reports it blocked - proves the guard is the fix
    assertThat(jobRepository.isJobNextInDispatchOrder(later.getId())).isFalse();
}

@Test
void guardedQuery_earlierPendingJobWithAPendingStepStillBlocks() {
    Workspace wsA = newWorkspace();
    Workspace wsB = newWorkspace();
    Job earlier = newJob(wsA, JobStatus.pending);
    newStep(earlier, 100, JobStatus.pending);
    Job later = newJob(wsB, JobStatus.pending);
    newStep(later, 100, JobStatus.pending);

    assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isFalse();
}

@Test
void guardedQuery_earlierUninitialisedPendingJobWithNoStepsStillBlocks() {
    Workspace wsA = newWorkspace();
    Workspace wsB = newWorkspace();
    Job earlier = newJob(wsA, JobStatus.pending);           // steps not created yet
    Job later = newJob(wsB, JobStatus.pending);
    newStep(later, 100, JobStatus.pending);

    assertThat(jobRepository.isJobNextInDispatchOrderExecutable(later.getId())).isFalse();
}

@Test
void guardedQuery_earlierRunningJobMidApplyStillBlocksItsWorkspaceSuccessor() {
    Workspace ws = newWorkspace();
    Job running = newJob(ws, JobStatus.running);            // executor owns it; step is running
    newStep(running, 100, JobStatus.running);
    Job later = newJob(ws, JobStatus.pending);
    newStep(later, 100, JobStatus.pending);

    assertThat(jobRepository.findNextDispatchableExecutableJobId()).isNotEqualTo(later.getId());
}

@Test
void guardedQuery_fifoPreservedAmongGenuinelyEligibleJobs() {
    Workspace wsA = newWorkspace();
    Workspace wsB = newWorkspace();
    Job first = newJob(wsA, JobStatus.pending);
    newStep(first, 100, JobStatus.pending);
    Job second = newJob(wsB, JobStatus.approved);
    newStep(second, 100, JobStatus.pending);

    assertThat(jobRepository.findNextDispatchableExecutableJobId()).isEqualTo(first.getId());
    assertThat(jobRepository.isJobNextInDispatchOrderExecutable(second.getId())).isFalse();
}
```

- [ ] **Step 3: Run the tests, verify they fail**

Run: `mvn -pl api -Dtest=JobDispatchOrderRepositoryIntegrationTest test`
Expected: compile failure — `isJobNextInDispatchOrderExecutable` / `findNextDispatchableExecutableJobId` do not exist.

- [ ] **Step 4: Add the guarded queries to `JobRepository`**

Add below the existing `isJobNextInDispatchOrder` / `findNextDispatchableJobId`. The only change from the originals is the extra clause requiring an earlier `pending`/`approved` job to have either **no steps at all** or **at least one pending step** before it counts as a blocker.

```java
/**
 * Guarded variant of {@link #isJobNextInDispatchOrder}: an earlier pending/approved job only
 * blocks when it still has an executable step - i.e. it has no steps yet (not initialised) OR
 * at least one step is still pending. A job with steps but none pending has had all its work
 * consumed and must not block the FIFO queue (design §3.6).
 */
@Query(value = "SELECT NOT EXISTS (" +
        "  SELECT 1 FROM job earlier" +
        "  WHERE earlier.id < :candidateJobId" +
        "    AND earlier.status IN ('pending', 'approved')" +
        "    AND earlier.deleted = false" +
        "    AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = earlier.id)" +
        "          OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = earlier.id AND s.status = 'pending') )" +
        "    AND NOT EXISTS (" +
        "      SELECT 1 FROM job blocker" +
        "      WHERE blocker.workspace_id = earlier.workspace_id" +
        "        AND blocker.id < earlier.id" +
        "        AND blocker.deleted = false" +
        "        AND blocker.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
        "    )" +
        ")", nativeQuery = true)
boolean isJobNextInDispatchOrderExecutable(@Param("candidateJobId") int candidateJobId);

/**
 * Guarded variant of {@link #findNextDispatchableJobId}: skips pending/approved heads that have
 * steps but no pending step (executable work already consumed). queue/running/waitingApproval
 * blockers are unaffected - the executor or a user owns those.
 */
@Query(value = "SELECT MIN(j.id) FROM job j" +
        " WHERE j.status IN ('pending', 'approved')" +
        "   AND j.deleted = false" +
        "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id)" +
        "         OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id AND s.status = 'pending') )" +
        "   AND NOT EXISTS (" +
        "     SELECT 1 FROM job earlier" +
        "     WHERE earlier.workspace_id = j.workspace_id" +
        "       AND earlier.id < j.id" +
        "       AND earlier.deleted = false" +
        "       AND earlier.status NOT IN (" + TERMINAL_JOB_STATUSES + ")" +
        "       AND ( earlier.status NOT IN ('pending','approved')" +
        "             OR NOT EXISTS (SELECT 1 FROM step s2 WHERE s2.job_id = earlier.id)" +
        "             OR EXISTS (SELECT 1 FROM step s2 WHERE s2.job_id = earlier.id AND s2.status = 'pending') )" +
        "   )", nativeQuery = true)
Integer findNextDispatchableExecutableJobId();
```

- [ ] **Step 5: Run the tests, verify they pass**

Run: `mvn -pl api -Dtest=JobDispatchOrderRepositoryIntegrationTest test`
Expected: PASS (new cases + all pre-existing cases in the class).

- [ ] **Step 6: Document the flags in `application.properties`**

Add near the other `io.terrakube.api.plugin.scheduler.*` entries:

```properties
# --- Zero-pending job reconciliation (design doc 2026-09-02) ---
# Sweep calls the reconciliation routine + admin endpoint may apply transitions.
io.terrakube.api.scheduler.reconciliation.sweep-enabled=true
# Sweep applies deterministic terminal transitions (false = dry-run: metrics/logs only).
io.terrakube.api.scheduler.reconciliation.auto-remediate=true
# Use the guarded executor-admission queries.
io.terrakube.api.scheduler.reconciliation.admission-guard-enabled=true
# Age (seconds) past which a stuck zero-pending job is flagged stale.
io.terrakube.api.scheduler.reconciliation.anomaly-grace-seconds=300
```

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/ReconciliationProperties.java \
        api/src/main/java/io/terrakube/api/repository/JobRepository.java \
        api/src/main/resources/application.properties \
        api/src/test/java/io/terrakube/api/plugin/scheduler/JobDispatchOrderRepositoryIntegrationTest.java
git commit -m "feat: guarded executor-admission queries + reconciliation flags

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 3: `JobReconciliationService` — the shared routine

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/ReconciliationResult.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationMetrics.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationService.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationServiceTest.java`

**Interfaces:**
- Consumes: `JobTerminalStateDeriver.derive`, `DerivedOutcome` (Task 1).
- Produces:
  - `enum ReconciliationDisposition { APPLIED, ALREADY_TERMINAL, DRY_RUN, HELD_ANOMALY, SKIPPED_HAS_WORK, RACE }`
  - `record ReconciliationResult(int jobId, JobStatus currentStatus, DerivedOutcome derivedOutcome, JobStatus targetStatus /*nullable*/, ReconciliationDisposition disposition, List<StepEvidence> evidence)` with `record StepEvidence(int stepNumber, JobStatus status)`.
  - `ReconciliationResult JobReconciliationService.reconcile(int jobId, boolean dryRun)`.
  - `List<ReconciliationResult> JobReconciliationService.report()` — read-only, one entry per non-terminal job with zero pending steps and ≥1 step (used by the admin endpoint GET and the sweep's discovery).
- Depends on (constructor, all Spring beans that already exist): `JobRepository`, `StepRepository`, `WorkspaceRepository`, `JobTerminalStateDeriver`, `JobNotificationTrigger`, `ScheduleJobService`, `org.quartz.Scheduler`, `JobReconciliationMetrics`.

> **Design note (revised during execution):** the spec's required reconciliation side-effects (§3.4 item 3) are: status update, workspace last-run status "through the normal status-update path", one notification event, trigger removal after commit. That is **all** the service does. VCS commit-status, PR comments and job-history pruning are `completeJob`-specific extras the spec does *not* require for reconciliation — `ScheduleJob` keeps doing those on its own inline path (Task 4), and the sweep path legitimately skips them (a stale zombie needs no fresh VCS push). No extraction from `ScheduleJob`; the 3-line workspace-status update is inlined in the service exactly as `JobManageHook` and `JobReconciliationSweep` already do it independently.

- [ ] **Step 1: Write `ReconciliationResult` + `JobReconciliationMetrics`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.rs.job.JobStatus;

import java.util.List;

public record ReconciliationResult(
        int jobId,
        JobStatus currentStatus,
        DerivedOutcome derivedOutcome,
        JobStatus targetStatus,               // nullable: only for terminal transitions
        ReconciliationDisposition disposition,
        List<StepEvidence> evidence) {

    public record StepEvidence(int stepNumber, JobStatus status) {}

    public enum ReconciliationDisposition {
        APPLIED, ALREADY_TERMINAL, DRY_RUN, HELD_ANOMALY, SKIPPED_HAS_WORK, RACE
    }
}
```

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.rs.job.JobStatus;
import org.springframework.stereotype.Component;

/** Thin wrapper so metric names stay in one place. Prometheus renders the dots as underscores. */
@Component
public class JobReconciliationMetrics {

    private final MeterRegistry registry;

    public JobReconciliationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void observedZeroPendingNonTerminal(JobStatus currentStatus) {
        registry.counter("terrakube.scheduler.zero.pending.nonterminal",
                "status", currentStatus.name()).increment();
    }

    public void reconciled(JobStatus targetStatus) {
        registry.counter("terrakube.scheduler.zero.pending.reconciliations",
                "outcome", targetStatus.name()).increment();
    }

    public void anomaly() {
        registry.counter("terrakube.scheduler.reconciliation.anomalies").increment();
    }

    public void quartzTriggerRecreated() {
        registry.counter("terrakube.scheduler.quartz.trigger.recreated").increment();
    }

    public void quartzTriggerRace() {
        registry.counter("terrakube.scheduler.quartz.trigger.races").increment();
    }
}
```

- [ ] **Step 3: Write the failing service test**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.ReconciliationDisposition;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quartz.Scheduler;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class JobReconciliationServiceTest {

    JobRepository jobRepository;
    StepRepository stepRepository;
    WorkspaceRepository workspaceRepository;
    JobNotificationTrigger jobNotificationTrigger;
    ScheduleJobService scheduleJobService;
    Scheduler scheduler;
    JobReconciliationService subject;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        stepRepository = mock(StepRepository.class, new FailUnkownMethod<StepRepository>());
        workspaceRepository = mock(WorkspaceRepository.class, new FailUnkownMethod<WorkspaceRepository>());
        jobNotificationTrigger = mock(JobNotificationTrigger.class);
        scheduleJobService = mock(ScheduleJobService.class, new FailUnkownMethod<ScheduleJobService>());
        scheduler = mock(Scheduler.class, new FailUnkownMethod<Scheduler>());
        lenient().doReturn(true).when(scheduler).deleteJob(any());
        lenient().doAnswer(i -> i.getArgument(0)).when(jobRepository).save(any());
        lenient().doAnswer(i -> i.getArgument(0)).when(workspaceRepository).save(any());
        lenient().doReturn(null).when(jobRepository).findNextDispatchableExecutableJobId();
        subject = new JobReconciliationService(jobRepository, stepRepository, workspaceRepository,
                new JobTerminalStateDeriver(), jobNotificationTrigger, scheduleJobService,
                scheduler, new JobReconciliationMetrics(new SimpleMeterRegistry()));
    }

    private Job job(int id, JobStatus status) {
        Job j = new Job();
        j.setId(id);
        j.setStatus(status);
        j.setWorkspace(new Workspace());
        return j;
    }

    private Step step(JobStatus status, int number) {
        Step s = new Step();
        s.setId(UUID.randomUUID());
        s.setStepNumber(number);
        s.setStatus(status);
        return s;
    }

    @Test
    void approvedWithAllStepsCompletedTransitionsToCompletedOnce() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100), step(JobStatus.completed, 200)))
                .when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.APPLIED);
        assertThat(result.targetStatus()).isEqualTo(JobStatus.completed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.completed);
        verify(jobRepository).save(j);
        verify(jobNotificationTrigger, times(1)).notifyStatusChanged(j);
        verify(workspaceRepository, times(1)).save(j.getWorkspace());
        verify(scheduler, times(1)).deleteJob(any());
    }

    @Test
    void alreadyTerminalIsANoOpWithNoEvent() {
        Job j = job(755, JobStatus.completed);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.ALREADY_TERMINAL);
        verify(jobNotificationTrigger, never()).notifyStatusChanged(any());
        verify(scheduler, never()).deleteJob(any());
    }

    @Test
    void dryRunDoesNotTransition() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100))).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, true);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.DRY_RUN);
        assertThat(result.targetStatus()).isEqualTo(JobStatus.completed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.approved);
        verify(jobRepository, never()).save(any());
        verify(jobNotificationTrigger, never()).notifyStatusChanged(any());
    }

    @Test
    void pendingStepsFoundAfterLockMeansSkippedHasWork() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.pending, 200))).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.SKIPPED_HAS_WORK);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void anomalyIsHeldNotTransitioned() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.<Step>of()).when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.disposition()).isEqualTo(ReconciliationDisposition.HELD_ANOMALY);
        assertThat(j.getStatus()).isEqualTo(JobStatus.approved);
        verify(jobRepository, never()).save(any());
    }

    @Test
    void failedStepTransitionsToFailedNeverCompleted() {
        Job j = job(755, JobStatus.approved);
        doReturn(j).when(jobRepository).lockForUpdate(755);
        doReturn(List.of(step(JobStatus.completed, 100), step(JobStatus.failed, 200)))
                .when(stepRepository).findByJobId(755);

        ReconciliationResult result = subject.reconcile(755, false);

        assertThat(result.targetStatus()).isEqualTo(JobStatus.failed);
        assertThat(j.getStatus()).isEqualTo(JobStatus.failed);
    }
}
```

- [ ] **Step 4: Run the test, verify it fails**

Run: `mvn -pl api -Dtest=JobReconciliationServiceTest test`
Expected: compile failure — `JobReconciliationService` does not exist.

- [ ] **Step 5: Write `JobReconciliationService`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.notification.JobNotificationTrigger;
import io.terrakube.api.plugin.scheduler.ScheduleJobService;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.ReconciliationDisposition;
import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.StepEvidence;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.repository.StepRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.step.Step;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.JobKey;
import org.quartz.ObjectAlreadyExistsException;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.Date;
import java.util.List;

import static io.terrakube.api.plugin.scheduler.ScheduleJobService.PREFIX_JOB_CONTEXT;

/**
 * The single routine that reconciles a non-terminal job with zero pending executable steps to a
 * terminal status derived from its persisted step outcomes. Called by ScheduleJob (inline, when a
 * flow evaluation finds no next step), the 30s reconciliation sweep, and the admin endpoint.
 *
 * Idempotent and safe under two API replicas: {@link JobRepository#lockForUpdate} serialises
 * concurrent callers on the job row, and an already-terminal job short-circuits with no event.
 */
@Slf4j
@Service
@AllArgsConstructor
public class JobReconciliationService {

    private final JobRepository jobRepository;
    private final StepRepository stepRepository;
    private final WorkspaceRepository workspaceRepository;
    private final JobTerminalStateDeriver deriver;
    private final JobNotificationTrigger jobNotificationTrigger;
    private final ScheduleJobService scheduleJobService;
    private final Scheduler scheduler;
    private final JobReconciliationMetrics metrics;

    @Transactional
    public ReconciliationResult reconcile(int jobId, boolean dryRun) {
        Job job = jobRepository.lockForUpdate(jobId);
        List<Step> steps = stepRepository.findByJobId(jobId);
        List<StepEvidence> evidence = steps.stream()
                .sorted(Comparator.comparingInt(Step::getStepNumber))
                .map(s -> new StepEvidence(s.getStepNumber(), s.getStatus()))
                .toList();

        long pendingSteps = steps.stream().filter(s -> s.getStatus() == JobStatus.pending).count();
        if (pendingSteps > 0) {
            return new ReconciliationResult(jobId, job.getStatus(), null, null,
                    ReconciliationDisposition.SKIPPED_HAS_WORK, evidence);
        }

        DerivedOutcome outcome = deriver.derive(job, steps);
        metrics.observedZeroPendingNonTerminal(job.getStatus());

        switch (outcome) {
            case ALREADY_TERMINAL:
                return result(jobId, job, outcome, null, ReconciliationDisposition.ALREADY_TERMINAL, evidence);
            case RETAIN_WAITING_APPROVAL:
            case ANOMALY:
                metrics.anomaly();
                log.warn("Reconciliation held job {}: status={}, derived={}, steps={}",
                        jobId, job.getStatus(), outcome, evidence);
                return result(jobId, job, outcome, null, ReconciliationDisposition.HELD_ANOMALY, evidence);
            default:
                break; // terminal transition
        }

        JobStatus target = outcome.targetStatus().orElseThrow();
        if (dryRun) {
            return result(jobId, job, outcome, target, ReconciliationDisposition.DRY_RUN, evidence);
        }

        JobStatus from = job.getStatus();
        job.setStatus(target);
        jobRepository.save(job);
        jobNotificationTrigger.notifyStatusChanged(job);
        updateWorkspaceStatus(job);
        metrics.reconciled(target);
        log.info("Reconciled job {} from {} to {} (derived {})", jobId, from, target, outcome);

        deleteTriggerAfterCommit(jobId);
        return result(jobId, job, outcome, target, ReconciliationDisposition.APPLIED, evidence);
    }

    // Same 3-line update JobManageHook and JobReconciliationSweep already do independently: keeps
    // workspace.lastJobStatus in sync since a plain jobRepository.save bypasses Elide's hooks.
    private void updateWorkspaceStatus(Job job) {
        Workspace workspace = job.getWorkspace();
        if (workspace == null) {
            return;
        }
        workspace.setLastJobStatus(job.getStatus());
        workspace.setLastJobDate(new Date(System.currentTimeMillis()));
        workspaceRepository.save(workspace);
    }

    /** Read-only: every non-terminal job with >=1 step and zero pending steps, with its derived
     *  target. Used by the admin GET report and the sweep's discovery pass. */
    @Transactional(readOnly = true)
    public List<ReconciliationResult> report() {
        return jobRepository.findAllByStatusInOrderByIdAsc(
                        io.terrakube.api.plugin.scheduler.reconciliation.JobReconciliationSweep.ACTIVE_STATUSES).stream()
                .map(job -> {
                    List<Step> steps = stepRepository.findByJobId(job.getId());
                    if (steps.isEmpty() || steps.stream().anyMatch(s -> s.getStatus() == JobStatus.pending)) {
                        return null;
                    }
                    DerivedOutcome outcome = deriver.derive(job, steps);
                    List<StepEvidence> evidence = steps.stream()
                            .sorted(Comparator.comparingInt(Step::getStepNumber))
                            .map(s -> new StepEvidence(s.getStepNumber(), s.getStatus()))
                            .toList();
                    return new ReconciliationResult(job.getId(), job.getStatus(), outcome,
                            outcome.targetStatus().orElse(null),
                            outcome.isTerminalTransition() ? ReconciliationDisposition.DRY_RUN
                                    : ReconciliationDisposition.HELD_ANOMALY,
                            evidence);
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private ReconciliationResult result(int jobId, Job job, DerivedOutcome outcome, JobStatus target,
            ReconciliationDisposition disposition, List<StepEvidence> evidence) {
        return new ReconciliationResult(jobId, job.getStatus(), outcome, target, disposition, evidence);
    }

    private void deleteTriggerAfterCommit(int jobId) {
        Runnable delete = () -> {
            try {
                scheduler.deleteJob(new JobKey(PREFIX_JOB_CONTEXT + jobId));
                Integer next = jobRepository.findNextDispatchableExecutableJobId();
                if (next != null) {
                    scheduleJobService.createJobContextNow(jobRepository.getReferenceById(next));
                }
            } catch (ObjectAlreadyExistsException e) {
                metrics.quartzTriggerRace();
                log.info("Quartz trigger race while reconciling job {}: {}", jobId, e.getMessage());
            } catch (SchedulerException e) {
                metrics.quartzTriggerRace();
                log.warn("Could not remove Quartz trigger for reconciled job {}: {}", jobId, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }
}
```

> Note: the test in Step 3 runs without an active transaction, so `deleteTriggerAfterCommit` takes the `else` branch and calls `scheduler.deleteJob` synchronously — that is why the test asserts `verify(scheduler).deleteJob(...)` directly.

- [ ] **Step 6: Run the test, verify it passes**

Run: `mvn -pl api -Dtest=JobReconciliationServiceTest test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/ReconciliationResult.java \
        api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationMetrics.java \
        api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationService.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationServiceTest.java
git commit -m "feat: add shared JobReconciliationService for zero-pending jobs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 4: Wire `ScheduleJob` to the reconciliation service

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/plugin/scheduler/ScheduleJob.java`
- Modify: `api/src/main/java/io/terrakube/api/plugin/scheduler/ExecutorAvailabilityListener.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/ScheduleJobTest.java`

**Interfaces:**
- Consumes: `JobReconciliationService.reconcile(int, boolean)` + `ReconciliationResult` (Task 3), `ReconciliationProperties` + `JobRepository.isJobNextInDispatchOrderExecutable` / `findNextDispatchableExecutableJobId` (Task 2).
- Produces: no new public surface. `ScheduleJob.completeJob` is removed.

- [ ] **Step 1: Write the failing test cases**

Add to `ScheduleJobTest` (the class already has `runExecution(job)` style helpers — inspect for the exact entry point used by existing `approved`/`pending` tests). Add a `JobReconciliationService reconciliationService = mock(...)` field and pass it through `subject()`.

```java
@Test
void approvedJobWithNoNextFlowIsReconciledNotStranded() {
    Job job = job(JobStatus.approved);
    doReturn(job).when(tclService).initJobConfiguration(job);
    doReturn(null).when(tclService).getNextFlow(job);
    doReturn(new ReconciliationResult(job.getId(), JobStatus.approved, DerivedOutcome.COMPLETED,
            JobStatus.completed, ReconciliationResult.ReconciliationDisposition.APPLIED, List.of()))
            .when(reconciliationService).reconcile(job.getId(), false);
    // ... existing "canProceed" stubs (no earlier blocking jobs) ...

    boolean deschedule = subject().runExecution(job);

    assertThat(deschedule).isTrue();
    verify(reconciliationService, times(1)).reconcile(job.getId(), false);
}

@Test
void approvedJobReconciliationAnomalyKeepsTheTrigger() {
    Job job = job(JobStatus.approved);
    doReturn(job).when(tclService).initJobConfiguration(job);
    doReturn(null).when(tclService).getNextFlow(job);
    doReturn(new ReconciliationResult(job.getId(), JobStatus.approved, DerivedOutcome.ANOMALY,
            null, ReconciliationResult.ReconciliationDisposition.HELD_ANOMALY, List.of()))
            .when(reconciliationService).reconcile(job.getId(), false);

    boolean deschedule = subject().runExecution(job);

    assertThat(deschedule).isFalse();
}

@Test
void pendingJobWithNoNextFlowDelegatesToReconciliationService() {
    Job job = job(JobStatus.pending);
    job.setPlanChanges(true);
    doReturn(job).when(tclService).initJobConfiguration(job);
    doReturn(null).when(tclService).getNextFlow(job);
    doReturn(new ReconciliationResult(job.getId(), JobStatus.pending, DerivedOutcome.COMPLETED,
            JobStatus.completed, ReconciliationResult.ReconciliationDisposition.APPLIED, List.of()))
            .when(reconciliationService).reconcile(job.getId(), false);

    boolean deschedule = subject().runExecution(job);

    assertThat(deschedule).isTrue();
    verify(reconciliationService).reconcile(job.getId(), false);
}
```

Also update every existing `ScheduleJobTest` case that reached the old `completeJob` path (search the file for `completeJob`, `JobStatus.completed` verifications on the pending-no-flow path) to instead stub `reconciliationService.reconcile(...)` and assert delegation. Existing cases that don't hit the empty-flow branch are unaffected apart from the `subject()` constructor arg.

- [ ] **Step 2: Run tests, verify the new ones fail**

Run: `mvn -pl api -Dtest=ScheduleJobTest test`
Expected: compile failure — `subject()` constructor arity / `reconciliationService` unknown.

- [ ] **Step 3: Modify `ScheduleJob`**

- Add constructor fields `JobReconciliationService jobReconciliationService` and `ReconciliationProperties reconciliationProperties` (it is `@AllArgsConstructor` — add them to the field list; update every `new ScheduleJob(...)` call site — there is only the test's `subject()` and Spring's own injection).
- In `executePendingJob`, replace:

```java
} else {
    completeJob(job);
    deleteOldJobs(job);
}
return true;
```

with:

```java
} else {
    return descheduleForReconciliation(job);
}
```

- In `executeApprovedJobs`, after the `if (flow.isPresent()) { ... }` block, replace the bare `return true;` with:

```java
return descheduleForReconciliation(job);
```

- Add:

```java
// Delegates a job that has run out of executable steps to the shared reconciliation routine.
// Returns whether the Quartz trigger should be removed: true once the job is terminal,
// false while it is an anomaly / still racing so the existing 30s trigger keeps retrying
// (the guarded admission query keeps such a job from blocking others in the meantime).
private boolean descheduleForReconciliation(Job job) {
    ReconciliationResult result = jobReconciliationService.reconcile(job.getId(), false);
    return switch (result.disposition()) {
        case APPLIED, ALREADY_TERMINAL -> true;
        case HELD_ANOMALY, SKIPPED_HAS_WORK, RACE, DRY_RUN -> false;
    };
}
```

- Delete the `completeJob` method entirely.
- `isNextInDispatchOrder`: select the query by the flag:

```java
boolean oldest = reconciliationProperties.isAdmissionGuardEnabled()
        ? jobRepository.isJobNextInDispatchOrderExecutable(job.getId())
        : jobRepository.isJobNextInDispatchOrder(job.getId());
if (!oldest) { ... }
```

- `wakeNextDispatchableJob`: same flag selection between `findNextDispatchableExecutableJobId()` and `findNextDispatchableJobId()`.
- In `ExecutorAvailabilityListener.onMessage`: inject `ReconciliationProperties`, select `findNextDispatchableExecutableJobId()` vs `findNextDispatchableJobId()` by the flag.

- [ ] **Step 4: Run tests, verify pass**

Run: `mvn -pl api -Dtest=ScheduleJobTest,ExecutorAvailabilityListenerTest test`
Expected: PASS (all cases, new + existing).

- [ ] **Step 5: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/scheduler/ScheduleJob.java \
        api/src/main/java/io/terrakube/api/plugin/scheduler/ExecutorAvailabilityListener.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/ScheduleJobTest.java
git commit -m "feat: scheduler delegates out-of-steps jobs to reconciliation routine

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 5: Wire `JobReconciliationSweep` to the reconciliation service

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweep.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepTest.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepIntegrationTest.java`

**Interfaces:**
- Consumes: `JobReconciliationService.reconcile` (Task 3), `ReconciliationProperties` (Task 2), `JobReconciliationMetrics` (Task 3).
- Produces: no new public surface.

- [ ] **Step 1: Write the failing unit test cases**

Add to `JobReconciliationSweepTest`. Add mocks: `JobReconciliationService reconciliationService`, `ReconciliationProperties properties` (real instance with defaults is fine), `JobReconciliationMetrics metrics` (real, `new SimpleMeterRegistry()`). Update `subject()` constructor call.

```java
@Test
void reconcilesAZombieApprovedJobBeforeReconcilingItsTrigger() throws Exception {
    Job zombie = job(30, JobStatus.approved);
    doReturn(List.of(zombie)).when(jobRepository)
            .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
    doReturn(List.of(step(JobStatus.completed))).when(stepRepository).findByJobId(30);
    doReturn(true).when(valueOperations).setIfAbsent(any(), any(), any()); // per-job lock acquired
    doReturn(true).when(redisTemplate).delete(anyString());
    doReturn(new ReconciliationResult(30, JobStatus.approved, DerivedOutcome.COMPLETED,
            JobStatus.completed, ReconciliationResult.ReconciliationDisposition.APPLIED, List.of()))
            .when(reconciliationService).reconcile(30, false); // autoRemediate default true

    subject().execute(null);

    verify(reconciliationService, times(1)).reconcile(30, false);
}

@Test
void runsReconcileInDryRunWhenAutoRemediateIsOff() throws Exception {
    properties.setAutoRemediate(false);
    Job zombie = job(31, JobStatus.approved);
    doReturn(List.of(zombie)).when(jobRepository)
            .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
    doReturn(List.of(step(JobStatus.completed))).when(stepRepository).findByJobId(31);
    doReturn(true).when(valueOperations).setIfAbsent(any(), any(), any());
    doReturn(true).when(redisTemplate).delete(anyString());
    doReturn(new ReconciliationResult(31, JobStatus.approved, DerivedOutcome.COMPLETED,
            JobStatus.completed, ReconciliationResult.ReconciliationDisposition.DRY_RUN, List.of()))
            .when(reconciliationService).reconcile(31, true);

    subject().execute(null);

    verify(reconciliationService, times(1)).reconcile(31, true);
}

@Test
void doesNotReconcileAJobThatStillHasAPendingStep() throws Exception {
    Job working = job(32, JobStatus.approved);
    doReturn(List.of(working)).when(jobRepository)
            .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
    doReturn(List.of(step(JobStatus.pending))).when(stepRepository).findByJobId(32);
    doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_32"));

    subject().execute(null);

    verify(reconciliationService, never()).reconcile(anyInt(), anyBoolean());
}

@Test
void doesNotReconcileWhenSweepDisabled() throws Exception {
    properties.setSweepEnabled(false);
    Job zombie = job(33, JobStatus.approved);
    doReturn(List.of(zombie)).when(jobRepository)
            .findAllByStatusInOrderByIdAsc(JobReconciliationSweep.ACTIVE_STATUSES);
    doReturn(true).when(scheduler).checkExists(new JobKey("TerrakubeV2_Job_33"));

    subject().execute(null);

    verify(reconciliationService, never()).reconcile(anyInt(), anyBoolean());
}
```

Add a `step(JobStatus)` helper to the test if not present.

- [ ] **Step 2: Run, verify fail**

Run: `mvn -pl api -Dtest=JobReconciliationSweepTest test`
Expected: compile failure.

- [ ] **Step 3: Modify `JobReconciliationSweep.execute`**

```java
@Transactional
@Override
public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
    boolean redisRecentlyRestarted = isRedisWithinWarmupPeriod();
    for (Job job : jobRepository.findAllByStatusInOrderByIdAsc(ACTIVE_STATUSES)) {
        if (properties.isSweepEnabled()) {
            reconcileZeroPendingJob(job);
        }
        reconcileTrigger(job);
        if (isExecutorOwnedStatus(job.getStatus())) {
            failIfExecutorHeartbeatExpired(job, redisRecentlyRestarted);
        }
    }
}

private void reconcileZeroPendingJob(Job job) {
    List<Step> steps = stepRepository.findByJobId(job.getId());
    if (steps.isEmpty() || steps.stream().anyMatch(s -> s.getStatus() == JobStatus.pending)) {
        return; // uninitialised or still has executable work - not our case
    }
    if (!acquirePerJobLock(job.getId())) {
        return; // another replica / the scheduler is handling this job right now
    }
    try {
        reconciliationService.reconcile(job.getId(), !properties.isAutoRemediate());
    } finally {
        releasePerJobLock(job.getId());
    }
}
```

`acquirePerJobLock` / `releasePerJobLock`: reuse the exact `EXECUTION_LOCK_PREFIX` + `EXECUTION_LOCK_TTL` semantics from `ScheduleJob` (SETNX with TTL, fail-closed on `DataAccessException` = return false). Copy the two private helpers in (or extract a small `JobExecutionLock` component and use it from both — cleaner, do that if the review in Task 4 didn't already).

In `reconcileTrigger`, on the successful `createJobContext` call add `metrics.quartzTriggerRecreated();`, and in the `ObjectAlreadyExistsException` catch add `metrics.quartzTriggerRace();`.

Add `ReconciliationProperties properties`, `JobReconciliationService reconciliationService`, `JobReconciliationMetrics metrics` to the `@AllArgsConstructor` field list.

- [ ] **Step 4: Run unit tests, verify pass**

Run: `mvn -pl api -Dtest=JobReconciliationSweepTest test`
Expected: PASS (new + existing).

- [ ] **Step 5: Add the integration test case**

Add to `JobReconciliationSweepIntegrationTest` (non-`@Transactional`, real Postgres + real sweep thread — follow the existing case's structure):

```java
@Test
void aZombieApprovedJobWithNoPendingStepsIsReconciledToCompletedBySweep() throws Exception {
    Workspace workspace = /* build + save as in the existing test */;
    Job zombie = new Job();
    zombie.setOrganization(organization);
    zombie.setWorkspace(workspace);
    zombie.setStatus(JobStatus.approved);
    zombie = jobRepository.save(zombie);
    Step done = new Step();
    done.setJob(zombie);
    done.setStepNumber(100);
    done.setStatus(JobStatus.completed);
    stepRepository.save(done);

    int id = zombie.getId();
    long deadline = System.currentTimeMillis() + 35_000;
    boolean reconciled = false;
    while (System.currentTimeMillis() < deadline) {
        JobStatus status = jobRepository.findById(id).map(Job::getStatus).orElse(null);
        if (status == JobStatus.completed) { reconciled = true; break; }
        Thread.sleep(500);
    }
    assertThat(reconciled).isTrue();
    assertThat(scheduler.checkExists(new JobKey(ScheduleJobService.PREFIX_JOB_CONTEXT + id))).isFalse();
}
```

The integration test context needs Redis. The existing sweep IT `@MockitoBean`s `RedisTemplate` — configure the mock so `execute(RedisCallback)` returns an uptime `Properties` (comfortably up), `opsForValue().setIfAbsent(...)` returns `true`, and `delete(...)` returns `true`, so `reconcileZeroPendingJob` can take its per-job lock. Add those `lenient()` stubs in a `@BeforeEach`.

- [ ] **Step 6: Run the integration test**

Run: `mvn -pl api -Dtest=JobReconciliationSweepIntegrationTest test` (Docker required)
Expected: PASS (new + existing case).

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweep.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepTest.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepIntegrationTest.java
git commit -m "feat: reconciliation sweep repairs zero-pending zombie jobs

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 6: `SchedulerQueueMetrics` — queue depth / head age / head job gauges

**Files:**
- Modify: `api/src/main/java/io/terrakube/api/repository/JobRepository.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerQueueMetrics.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerQueueMetricsTest.java`

**Interfaces:**
- Consumes: `JobRepository.findNextDispatchableExecutableJobId` (Task 2).
- Produces:
  - `int JobRepository.countDispatchEligibleJobs()` — number of jobs the guarded query considers eligible right now.
  - `java.util.Date JobRepository.findCreatedDateById(int jobId)` (or reuse `findById`) for head age.
  - Gauges `terrakube.scheduler.executor.queue.depth`, `terrakube.scheduler.executor.queue.head.age.seconds`, `terrakube.scheduler.executor.queue.head.job`.

- [ ] **Step 1: Add the repository read methods**

```java
@Query(value = "SELECT COUNT(*) FROM job j" +
        " WHERE j.status IN ('pending','approved')" +
        "   AND j.deleted = false" +
        "   AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id)" +
        "         OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = j.id AND s.status = 'pending') )",
        nativeQuery = true)
int countDispatchEligibleJobs();
```

Head age: the metric class can call `findNextDispatchableExecutableJobId()` then `findById(id).getCreatedDate()`. No new query needed.

- [ ] **Step 2: Write the failing test**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.terrakube.api.helpers.FailUnkownMethod;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SchedulerQueueMetricsTest {

    JobRepository jobRepository;
    MeterRegistry registry;

    @BeforeEach
    void setup() {
        jobRepository = mock(JobRepository.class, new FailUnkownMethod<JobRepository>());
        registry = new SimpleMeterRegistry();
    }

    @Test
    void exposesDepthAndHeadGauges() {
        doReturn(3).when(jobRepository).countDispatchEligibleJobs();
        doReturn(42).when(jobRepository).findNextDispatchableExecutableJobId();
        Job head = new Job();
        head.setId(42);
        head.setCreatedDate(new Date(System.currentTimeMillis() - 120_000));
        doReturn(Optional.of(head)).when(jobRepository).findById(42);

        SchedulerQueueMetrics metrics = new SchedulerQueueMetrics(jobRepository, registry);
        metrics.registerGauges();

        assertThat(registry.get("terrakube.scheduler.executor.queue.depth").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.job").gauge().value()).isEqualTo(42.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.age.seconds").gauge().value())
                .isBetween(110.0, 130.0);
    }

    @Test
    void emptyQueueReportsZeroDepthAndMinusOneHead() {
        doReturn(0).when(jobRepository).countDispatchEligibleJobs();
        doReturn(null).when(jobRepository).findNextDispatchableExecutableJobId();

        SchedulerQueueMetrics metrics = new SchedulerQueueMetrics(jobRepository, registry);
        metrics.registerGauges();

        assertThat(registry.get("terrakube.scheduler.executor.queue.depth").gauge().value()).isEqualTo(0.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.job").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("terrakube.scheduler.executor.queue.head.age.seconds").gauge().value()).isEqualTo(0.0);
    }
}
```

- [ ] **Step 3: Run, verify fail**

Run: `mvn -pl api -Dtest=SchedulerQueueMetricsTest test`
Expected: compile failure.

- [ ] **Step 4: Write `SchedulerQueueMetrics`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

@Component
public class SchedulerQueueMetrics {

    private final JobRepository jobRepository;
    private final MeterRegistry meterRegistry;

    public SchedulerQueueMetrics(JobRepository jobRepository, MeterRegistry meterRegistry) {
        this.jobRepository = jobRepository;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void registerGauges() {
        Gauge.builder("terrakube.scheduler.executor.queue.depth", jobRepository,
                        JobRepository::countDispatchEligibleJobs)
                .description("Jobs currently eligible for the shared executor pool (guarded FIFO query)")
                .register(meterRegistry);
        Gauge.builder("terrakube.scheduler.executor.queue.head.job", this, SchedulerQueueMetrics::headJobId)
                .description("Numeric id of the eligible FIFO head job, -1 if the queue is empty")
                .register(meterRegistry);
        Gauge.builder("terrakube.scheduler.executor.queue.head.age.seconds", this,
                        SchedulerQueueMetrics::headAgeSeconds)
                .description("Age in seconds of the eligible FIFO head job, 0 if the queue is empty")
                .register(meterRegistry);
    }

    static double headJobId(SchedulerQueueMetrics self) {
        Integer id = self.jobRepository.findNextDispatchableExecutableJobId();
        return id == null ? -1 : id;
    }

    static double headAgeSeconds(SchedulerQueueMetrics self) {
        Integer id = self.jobRepository.findNextDispatchableExecutableJobId();
        if (id == null) {
            return 0;
        }
        return self.jobRepository.findById(id)
                .map(Job::getCreatedDate)
                .map(created -> Math.max(0, (System.currentTimeMillis() - created.getTime()) / 1000.0))
                .orElse(0.0);
    }
}
```

- [ ] **Step 5: Run, verify pass**

Run: `mvn -pl api -Dtest=SchedulerQueueMetricsTest test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add api/src/main/java/io/terrakube/api/repository/JobRepository.java \
        api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerQueueMetrics.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerQueueMetricsTest.java
git commit -m "feat: executor queue depth/head-age/head-job gauges

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 7: Protected recovery endpoint

**Files:**
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationAccessService.java`
- Create: `api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationController.java`
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationControllerTest.java`

**Interfaces:**
- Consumes: `JobReconciliationService.report()` and `reconcile(int, boolean)` (Task 3).
- Produces: HTTP `GET /admin/v1/scheduler/reconciliation` and `POST /admin/v1/scheduler/reconciliation`.

- [ ] **Step 1: Write `SchedulerReconciliationAccessService`**

Mirror `NotificationConfigurationAccessService`'s auth style (`JwtAuthenticationToken` token attributes) and `isSuperService`'s rule (internal issuer OR a group equal to the configured instance owner).

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SchedulerReconciliationAccessService {

    private final String instanceOwner;

    public SchedulerReconciliationAccessService(@Value("${io.terrakube.owner}") String instanceOwner) {
        this.instanceOwner = instanceOwner;
    }

    public boolean isAdmin(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            return false;
        }
        Object iss = token.getTokenAttributes().get("iss");
        if ("TerrakubeInternal".equals(iss)) {
            return true;
        }
        Object groups = token.getTokenAttributes().get("groups");
        return groups instanceof List<?> list && list.contains(instanceOwner);
    }
}
```

- [ ] **Step 2: Write the failing controller test**

Follow the repo's controller-test pattern: `class SchedulerReconciliationControllerTest extends ServerApplicationTests` (full `@SpringBootTest` + RestAssured, already wires a mock `redisTemplate`). Override the `JobReconciliationService` bean with `@MockitoBean` (or `@MockitoSpyBean` if you want the real `report()` against seeded data). Auth: `generatePAT("TERRAKUBE_ADMIN")` is the admin token (test props set `io.terrakube.owner=TERRAKUBE_ADMIN`); `generatePAT("TERRAKUBE_DEVELOPERS")` is a non-admin; `generateSystemToken()` is the internal-issuer token.

```java
@Test
void reportRequiresAdminGroup() {
    given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_DEVELOPERS"))
        .when().get("/admin/v1/scheduler/reconciliation")
        .then().statusCode(403);
}

@Test
void reportReturnsStuckJobsForAdmin() {
    when(jobReconciliationService.report()).thenReturn(List.of(
        new ReconciliationResult(755, JobStatus.approved, DerivedOutcome.COMPLETED, JobStatus.completed,
            ReconciliationResult.ReconciliationDisposition.DRY_RUN, List.of())));
    given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
        .when().get("/admin/v1/scheduler/reconciliation")
        .then().statusCode(200).body("[0].jobId", equalTo(755)).body("[0].targetStatus", equalTo("completed"));
}

@Test
void applyWithoutConfirmIsRejected() {
    given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
        .contentType("application/json").body("{\"confirm\":false,\"jobIds\":[755]}")
        .when().post("/admin/v1/scheduler/reconciliation")
        .then().statusCode(400);
    verify(jobReconciliationService, never()).reconcile(anyInt(), anyBoolean());
}

@Test
void applyRunsOnlyDeterministicTargetsAndSkipsAnomalies() {
    when(jobReconciliationService.report()).thenReturn(List.of(
        new ReconciliationResult(755, JobStatus.approved, DerivedOutcome.COMPLETED, JobStatus.completed,
            ReconciliationResult.ReconciliationDisposition.DRY_RUN, List.of()),
        new ReconciliationResult(756, JobStatus.approved, DerivedOutcome.ANOMALY, null,
            ReconciliationResult.ReconciliationDisposition.HELD_ANOMALY, List.of())));
    when(jobReconciliationService.reconcile(755, false)).thenReturn(
        new ReconciliationResult(755, JobStatus.approved, DerivedOutcome.COMPLETED, JobStatus.completed,
            ReconciliationResult.ReconciliationDisposition.APPLIED, List.of()));
    given().headers("Authorization", "Bearer " + generatePAT("TERRAKUBE_ADMIN"))
        .contentType("application/json").body("{\"confirm\":true,\"jobIds\":[755,756]}")
        .when().post("/admin/v1/scheduler/reconciliation")
        .then().statusCode(200).body("size()", equalTo(1)).body("[0].jobId", equalTo(755));
    verify(jobReconciliationService, never()).reconcile(756, false);
}
```

- [ ] **Step 3: Run, verify fail**

Run: `mvn -pl api -Dtest=SchedulerReconciliationControllerTest test`
Expected: compile failure.

- [ ] **Step 4: Write `SchedulerReconciliationController`**

```java
package io.terrakube.api.plugin.scheduler.reconciliation;

import io.terrakube.api.plugin.scheduler.reconciliation.ReconciliationResult.ReconciliationDisposition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/admin/v1/scheduler/reconciliation")
public class SchedulerReconciliationController {

    private final JobReconciliationService reconciliationService;

    public SchedulerReconciliationController(JobReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    public record ApplyRequest(boolean confirm, List<Integer> jobIds) {}

    @GetMapping
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public List<ReconciliationResult> report() {
        return reconciliationService.report();
    }

    @PostMapping
    @PreAuthorize("@schedulerReconciliationAccessService.isAdmin(authentication)")
    public ResponseEntity<List<ReconciliationResult>> apply(@RequestBody ApplyRequest request) {
        if (!request.confirm()) {
            return ResponseEntity.badRequest().build();
        }
        List<ReconciliationResult> dryRun = reconciliationService.report();
        List<ReconciliationResult> applied = dryRun.stream()
                .filter(r -> request.jobIds() == null || request.jobIds().contains(r.jobId()))
                .filter(r -> r.derivedOutcome() != null && r.derivedOutcome().isTerminalTransition())
                .map(r -> {
                    ReconciliationResult result = reconciliationService.reconcile(r.jobId(), false);
                    log.info("Admin reconciliation applied: job {} {} -> {} ({})",
                            r.jobId(), result.currentStatus(), result.targetStatus(), result.disposition());
                    return result;
                })
                .toList();
        return ResponseEntity.ok(applied);
    }
}
```

- [ ] **Step 5: Security config check**

`/admin/v1/**` is not in `DexWebSecurityAdapter`'s permit list, so it already falls under `.anyRequest().authenticated()` — that is what we want (authenticated + `@PreAuthorize`). The other JSON `POST` controllers (`/notification/v1`, `/notification/v1/.../delivery`) work with only a bearer token and no CSRF token (see `NotificationDeliveryControllerTest`), so no security-config change is expected. If the `POST` test in Step 2 returns 403 with a valid admin token, add `"/admin/v1/**"` to the `csrf(crsf -> crsf.ignoringRequestMatchers(...))` list in `DexWebSecurityAdapter.filterChain` and re-run.

- [ ] **Step 6: Run tests, verify pass**

Run: `mvn -pl api -Dtest=SchedulerReconciliationControllerTest test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationAccessService.java \
        api/src/main/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationController.java \
        api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/SchedulerReconciliationControllerTest.java
git commit -m "feat: protected scheduler reconciliation report/apply endpoint

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 8: Concurrency integration test

**Files:**
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationConcurrencyIntegrationTest.java`

**Interfaces:**
- Consumes: `JobReconciliationService` (Task 3) via Spring context.

- [ ] **Step 1: Write the test**

`@SpringBootTest @ActiveProfiles("test") @Testcontainers`, not `@Transactional` (needs committed rows visible to a second thread). Mock Redis beans as the other reconciliation ITs do.

```java
@Test
void twoConcurrentReconcileCallsProduceExactlyOneTransition() throws Exception {
    // create org + workspace + an approved job with two completed steps, committed
    int jobId = createApprovedZombie();

    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<ReconciliationResult>> futures = new ArrayList<>();
    for (int i = 0; i < 2; i++) {
        futures.add(pool.submit(() -> {
            start.await();
            return reconciliationService.reconcile(jobId, false);
        }));
    }
    start.countDown();
    List<ReconciliationResult> results = new ArrayList<>();
    for (Future<ReconciliationResult> f : futures) results.add(f.get(10, TimeUnit.SECONDS));
    pool.shutdown();

    long applied = results.stream()
            .filter(r -> r.disposition() == ReconciliationResult.ReconciliationDisposition.APPLIED)
            .count();
    assertThat(applied).isEqualTo(1);
    assertThat(jobRepository.findById(jobId).get().getStatus()).isEqualTo(JobStatus.completed);
}
```

Assert the other call returns `ALREADY_TERMINAL` (it observed the committed transition under the row lock).

- [ ] **Step 2: Run, verify pass**

Run: `mvn -pl api -Dtest=JobReconciliationConcurrencyIntegrationTest test` (Docker required)
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationConcurrencyIntegrationTest.java
git commit -m "test: concurrent reconciliation produces one transition

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 9: End-to-end burst test + operator runbook

> **Execution note:** a standalone `ZeroPendingBurstIntegrationTest` shared a Spring context
> with `JobReconciliationSweepIntegrationTest` (dynamic-property customizers compared equal),
> and whichever class ran first had its Testcontainers Postgres torn down under the other. The
> end-to-end scenario (live sweep reconciles a stale `approved` head + guarded head query
> excludes it) was folded into `JobReconciliationSweepIntegrationTest.aZombieApprovedJobWithNoPendingStepsIsReconciledToCompletedBySweep`
> instead — same context, one container. No separate burst class.

**Files:**
- Test: `api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/JobReconciliationSweepIntegrationTest.java` (folded-in scenario)
- Create (NOT committed): `docs/ops/zero-pending-job-reconciliation.md`

**Interfaces:**
- Consumes: the full wired context (Tasks 1–7).

- [ ] **Step 1: Write the burst integration test**

`@SpringBootTest @ActiveProfiles("test") @Testcontainers`, not `@Transactional`. Real sweep thread runs. Mock the executor dispatch boundary: `@MockitoBean ExecutorService` (the scheduler's `io.terrakube.api.plugin.scheduler.job.tcl.executor.ExecutorService`) so `execute(...)` records the dispatched job id and does nothing else; mock Redis beans. Scenario:

```java
@Test
void aStaleApprovedHeadIsReconciledAndLaterJobsDispatch() throws Exception {
    Organization org = createOrg();
    // 1 workspace, a stale approved job with all steps completed (the "755")
    Workspace wsStale = createWorkspace(org);
    int stale = createApprovedJobWithCompletedSteps(wsStale);
    // 3 more workspaces each with a fresh pending job + one pending step (the "777"s)
    List<Integer> fresh = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
        fresh.add(createPendingJobWithPendingStep(createWorkspace(org)));
    }

    // wait for the sweep to reconcile the stale job
    awaitStatus(stale, JobStatus.completed, Duration.ofSeconds(35));

    // the guarded query now returns one of the fresh jobs as the head
    assertThat(jobRepository.findNextDispatchableExecutableJobId()).isIn(fresh);
    // and the stale job's trigger is gone
    assertThat(scheduler.checkExists(new JobKey(ScheduleJobService.PREFIX_JOB_CONTEXT + stale))).isFalse();
}
```

If driving real dispatch through Quartz proves flaky in the test window, assert the two invariants directly (stale job reconciled to `completed`; `findNextDispatchableExecutableJobId` returns a fresh job) rather than asserting on `ExecutorService.execute` interactions.

- [ ] **Step 2: Run, verify pass**

Run: `mvn -pl api -Dtest=ZeroPendingBurstIntegrationTest test` (Docker required)
Expected: PASS.

- [ ] **Step 3: Write the operator runbook** (`docs/ops/zero-pending-job-reconciliation.md`, do NOT commit)

Contents:
- Symptom: jobs pending while executors idle; log line `is not yet the oldest job waiting for the executor pool`.
- Diagnosis: `GET /admin/v1/scheduler/reconciliation` (needs an instance-owner / internal token) — shows stuck jobs, derived target, step evidence, queue-head age.
- Metrics to watch: `terrakube_scheduler_zero_pending_nonterminal_total`, `terrakube_scheduler_executor_queue_head_age_seconds`, `terrakube_scheduler_reconciliation_anomalies_total`, `quartz_jobs_executing`.
- Manual recovery: `POST /admin/v1/scheduler/reconciliation {"confirm":true,"jobIds":[...]}` — applies only deterministic completed/failed/cancelled/rejected; anomalies stay for investigation.
- Alert rules (PromQL):
  - `terrakube_scheduler_zero_pending_nonterminal_total` increasing AND `max_over_time(terrakube_scheduler_executor_queue_head_age_seconds[10m]) > <anomaly-grace-seconds>`.
  - `terrakube_scheduler_executor_queue_head_age_seconds > 300` AND `quartz_jobs_executing == 0` for 10m.
  - `increase(terrakube_scheduler_reconciliation_anomalies_total[1h]) > 0`.
- Rollout flag reference (the four `io.terrakube.api.scheduler.reconciliation.*` properties) and the recommended phase order from spec §3.10.

- [ ] **Step 4: Deliver the runbook to the user**

Use `SendUserFile` for `docs/ops/zero-pending-job-reconciliation.md`. Do not `git add` it.

- [ ] **Step 5: Commit the test only**

```bash
git add api/src/test/java/io/terrakube/api/plugin/scheduler/reconciliation/ZeroPendingBurstIntegrationTest.java
git commit -m "test: end-to-end burst with stale approved head job

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Task 10: Full-module verification

**Files:** none (verification only).

- [ ] **Step 1: Run the full API test suite**

Run: `mvn -pl api test`
Expected: BUILD SUCCESS. If any pre-existing test broke, fix it in the task that caused the break (most likely `ScheduleJobTest` / `JobManageHookTest` / `RemoteTfeServiceTest` from the `ScheduleJob` constructor change or the `completeJob` removal).

- [ ] **Step 2: Grep for leftovers**

Run: `grep -rn "completeJob" api/src/main` — Expected: no results (method fully removed).
Run: `grep -rn "isJobNextInDispatchOrder\b" api/src/main` — Expected: only the flag-guarded call sites in `ScheduleJob`.

- [ ] **Step 3: Commit any fixes, then report done**

```bash
git add -A && git commit -m "test: fix fallout from reconciliation wiring

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**

| Spec section | Task |
|---|---|
| §1.1 root cause (`executeApprovedJobs` fallback) | Task 4 |
| §1.2 no `noChanges` transition | Task 1 (deriver rules), Global Constraints |
| §3.2 `DerivedOutcome` / `ReconciliationResult` | Tasks 1, 3 |
| §3.3 derivation precedence | Task 1 |
| §3.4 `reconcile` (lock, re-read, derive, apply, afterCommit trigger delete, race counter) | Task 3 |
| §3.5 scheduler wiring + intentional behaviour change | Task 4 |
| §3.6 admission guard (both queries, uninitialised/running still block) | Task 2 |
| §3.7 sweep wiring + trigger counters + `sweep-enabled` gate | Task 5 |
| §3.8 recovery endpoint (GET report, POST confirmed, anomalies never applied, audit log) | Task 7 |
| §3.9 metrics (5 counters + 3 gauges) | Tasks 3, 5, 6 |
| §3.10 rollout flags + `application.properties` + phase order | Tasks 2, 9 |
| §4.1 deriver unit tests | Task 1 |
| §4.2 repository integration tests | Task 2 |
| §4.3 service integration tests | Task 3 (unit) + Task 9 (context) |
| §4.4 concurrency test | Task 8 |
| §4.5 `ScheduleJobTest` updates | Task 4 |
| §4.6 e2e burst | Task 9 |
| §5 acceptance criteria 1–8 | Tasks 1–9 collectively; criteria 6 & 8 = Tasks 5, 9 |
| Observability alert rules | Task 9 runbook |

**Placeholder scan:** the deriver, service, metrics, queries, and both `application.properties` blocks are given as complete code. Controller test (Task 7 Step 2) and runbook (Task 9 Step 3) are described by explicit assertion/content lists rather than verbatim code because they depend on the repo's existing `*ControllerTest` security scaffold — the implementer must copy that pattern; this is called out in-step.

**Type consistency:** `DerivedOutcome` (Task 1) is used with `.targetStatus()` / `.isTerminalTransition()` in Tasks 3 and 7. `ReconciliationResult` / `ReconciliationDisposition` (Task 3) fields (`jobId()`, `currentStatus()`, `derivedOutcome()`, `targetStatus()`, `disposition()`, `evidence()`) are used consistently in Tasks 4, 5, 7, 8, 9. `ReconciliationProperties` getters (`isSweepEnabled`, `isAutoRemediate`, `isAdmissionGuardEnabled`, `getAnomalyGraceSeconds`) match between Tasks 2, 4, 5. `JobRepository.isJobNextInDispatchOrderExecutable` / `findNextDispatchableExecutableJobId` / `countDispatchEligibleJobs` named identically in Tasks 2, 4, 6. `JobTerminalTransitionSupport` method names (`deleteOldJobs`, `updateWorkspaceStatus`, `updateJobStatusOnVcs`, `postPrCommentIfNeeded`) match between Tasks 3 and 4.

---

## Execution Handoff

See the top-of-file sub-skill requirement. Recommended: subagent-driven, fresh agent per task, review between tasks.
