# Zero-pending-step job reconciliation and queue liveness — design

Date: 2026-09-02
Status: approved for planning
Scope: full spec (shared reconciliation routine, admission-query guard, protected
recovery endpoint, metrics + alert docs, rollout flags)

## 1. Problem

During a burst of workspace runs, job `777` stayed `pending` while every executor
pod was Ready and idle. The FIFO admission check (`JobRepository.isJobNextInDispatchOrder`)
kept rejecting it because an older job (`755`, then `756`, `759`) was still counted
as an eligible queue candidate. Those jobs were `approved` with **zero pending
steps**. Their Quartz triggers were repeatedly recreated by the reconciliation
sweep and repeatedly removed themselves, never transitioning the job to a terminal
status. Result: head-of-line scheduler liveness failure, not executor capacity
exhaustion.

### 1.1 Root cause (confirmed in code)

`ScheduleJob.executeApprovedJobs(Job)` is missing the terminal-derivation fallback
that its sibling `executePendingJob(Job)` has:

- `executePendingJob` — when `tclService.getNextFlow(job)` returns `null`
  (no pending step, or no flow matches the pending step):
  `else { completeJob(job); deleteOldJobs(job); }` → job goes terminal.
- `executeApprovedJobs` — same condition falls straight through to `return true`.
  `return true` means "deschedule the Quartz trigger". The job is left `approved`
  forever.

`JobReconciliationSweep.reconcileTrigger` then sees the still-`approved` job (it is
in `ACTIVE_STATUSES`), finds no trigger, and recreates it. The recreated trigger
fires `executeApprovedJobs`, finds zero pending steps, deschedules again. Infinite
create/delete churn with no status change — exactly the incident evidence.

`isJobNextInDispatchOrder` counts every non-deleted `pending`/`approved` job as a
queue candidate, so the zombie permanently fails the "are you the oldest?" check
for later jobs. `waitingApproval`, `queue`, and `running` have narrower analogous
gaps; the sweep's executor-heartbeat check already partially covers `queue`/`running`.

### 1.2 Spec ambiguity resolved: `noChanges`

`JobStatus.noChanges` exists in the enum but is **never assigned** anywhere in the
scheduler or executor. A no-change plan is recorded by
`executor` `UpdateJobStatusImpl.updateJobStatus` (plan exit code 0) as
`status = "completed"`, `planChanges = false`. Read sites
(`ContextController`, `SlackPayloadBuilder`, the terminal-status SQL lists) treat
`noChanges` and `completed` identically.

Therefore the "established no-change terminal semantics" (spec invariant 4) **is**
`completed` with `planChanges = false`. The derivation in this design produces
`completed` for the no-change case and does **not** introduce `noChanges`
transitions.

## 2. Invariants (unchanged from the spec, restated against real enums)

1. A job with zero pending executable steps must not stay in `pending`,
   `approved`, `queue`, or `running` indefinitely.
2. The scheduler must never infer `completed` merely from "no pending steps";
   the target status is derived from persisted `step.status` values and the flow
   definition.
3. A job with a `failed` / `cancelled` / `rejected` step keeps the matching
   non-success outcome.
4. A no-change plan resolves to `completed` + `planChanges=false` (the established
   model — see §1.2).
5. Reconciliation of a zero-pending non-terminal job is atomic enough that it
   cannot drive repeated Quartz trigger create/delete.
6. Executor admission is never blocked by a job with no remaining executable step.
7. Reconciliation is idempotent, safe under two API replicas, never duplicates a
   dispatch, and never sends an apply without approval.

## 3. Approach

Chosen: **one shared routine** behind a new service, called by the scheduler, the
sweep, and the recovery endpoint. Rejected alternatives: folding derivation into
`TclService` / `ScheduleJobService` (muddies already-mixed responsibilities);
duplicating logic between sweep and scheduler (drifts — violates the spec's
"must use the same routine").

### 3.1 New / changed units

| Unit | Kind | Responsibility |
|---|---|---|
| `JobTerminalStateDeriver` | new `@Component` | Pure function `(Job, List<Step>) → DerivedOutcome`. No I/O. Flow config is not needed: the routine's precondition is `pendingSteps == 0`, and `initJobConfiguration` creates exactly one step per flow entry up front, so "all steps terminal-success" is equivalent to "all flow steps done". |
| `JobReconciliationService` | new `@Service` | `reconcile(int jobId, boolean dryRun) → ReconciliationResult`. Locks, re-reads, derives, applies transition through existing paths, deletes trigger after commit, emits counters. |
| `SchedulerQueueMetrics` | new `@Component` | Registers queue-depth / head-age / head-job gauges (mirrors `QuartzMetrics`). |
| `ReconciliationProperties` | new `@ConfigurationProperties` | Rollout flags, prefix `io.terrakube.api.scheduler.reconciliation`. |
| `SchedulerReconciliationController` | new `@RestController` | `/admin/v1/scheduler/reconciliation`, super-service secured. Report + confirmed-apply. |
| `ScheduleJob` | changed | Zero-pending branches delegate to `JobReconciliationService`. `completeJob` side effects move into the service. |
| `JobReconciliationSweep` | changed | Calls `JobReconciliationService.reconcile` for zero-pending jobs with ≥1 step, before `reconcileTrigger`. Wraps trigger create/race in counters. |
| `JobRepository` | changed | Admission queries gain the "has an executable step" predicate; new read methods for queue metrics and for listing zombie candidates. |

### 3.2 `DerivedOutcome` and `ReconciliationResult`

`JobTerminalStateDeriver` returns a `DerivedOutcome` (pure, from job + steps):

```
enum DerivedOutcome {
    ALREADY_TERMINAL,        // no transition, no event
    FAILED,                  // -> JobStatus.failed
    CANCELLED,               // -> JobStatus.cancelled
    REJECTED,                // -> JobStatus.rejected
    COMPLETED,               // -> JobStatus.completed  (covers no-change)
    RETAIN_WAITING_APPROVAL, // no transition; stays out of executor queue
    ANOMALY                  // no transition; operator-visible; excluded from dispatch
}
```

`JobReconciliationService.reconcile` returns a `ReconciliationResult` that carries
the `DerivedOutcome` plus the service-level disposition:

```
enum Disposition {
    APPLIED,            // a terminal transition was committed
    ALREADY_TERMINAL,   // deriver rule 1 - nothing to do
    DRY_RUN,            // would transition to <target>, not applied
    HELD_ANOMALY,       // ANOMALY or RETAIN_WAITING_APPROVAL - no transition
    SKIPPED_HAS_WORK,   // re-read after lock found pending steps again
    RACE               // Quartz contention; job state is durable, retried next pass
}
```

### 3.3 Derivation precedence

Invoked only when the job currently has `pendingSteps == 0`. Ordered:

1. `job.status ∈ {completed, failed, rejected, cancelled, noChanges}`
   → `ALREADY_TERMINAL`.
2. any `step.status == failed` → `FAILED`.
3. any `step.status == cancelled` → `CANCELLED`.
4. any `step.status == rejected` **or** `job.status == rejected` → `REJECTED`.
5. `job.status == waitingApproval` → `RETAIN_WAITING_APPROVAL`
   (also bump the anomaly *metric* — waitingApproval with 0 pending steps is
   irregular — but do not transition; the job is already excluded from the
   executor queue because its status is not `pending`/`approved`).
6. at least one step exists **and** every step ∈ `{completed, notExecuted}`
   → `COMPLETED`.
7. otherwise (no steps at all; a pending step whose flow does not resolve; any
   mixed / inconsistent state) → `ANOMALY`.

Rationale for 6: this is exactly what `ScheduleJob.completeJob` does today when
`getNextFlow` returns empty in the `pending` path; the no-change plan already
carries `step.status == completed` + `job.planChanges == false`, so it lands here
as `COMPLETED` with no special casing.

### 3.4 `JobReconciliationService.reconcile(int jobId, boolean dryRun)`

`@Transactional`. Steps:

1. `Job job = jobRepository.lockForUpdate(jobId)` — existing pessimistic write
   lock; forces a concurrent reconcile / dispatch on another replica to serialise.
2. Re-read `job.status` and `stepRepository.findByJobId(jobId)` **after** the lock.
   Recompute `pendingSteps`. If `pendingSteps > 0` → return `Disposition.SKIPPED_HAS_WORK`
   (a racing dispatch created/rescheduled steps; nothing to do).
3. `DerivedOutcome outcome = deriver.derive(job, steps)`.
4. Emit `zero_pending_nonterminal_total{status = job.status}` once here.
5. Branch:
   - `ALREADY_TERMINAL` → return, no event, no counter beyond step 4.
   - `RETAIN_WAITING_APPROVAL` → `reconciliation_anomalies_total`++, structured
     log, return (no transition).
   - `ANOMALY` → `reconciliation_anomalies_total`++, structured log with job id,
     current status, step statuses, derived reason; return (no transition).
   - `FAILED` / `CANCELLED` / `REJECTED` / `COMPLETED`:
     - if `dryRun` → record intended transition in `ReconciliationResult`, return.
     - else apply: `job.setStatus(target)`, `jobRepository.save(job)`,
       `jobNotificationTrigger.notifyStatusChanged(job)` — the same status-change
       notification path `completeJob` uses today; it is called exactly once
       because rule 1 (`ALREADY_TERMINAL`) short-circuits every subsequent
       `reconcile` for this job. `updateWorkspaceStatus(job)` through the normal path,
       `updateJobStatusOnVcs(job, <mapped>)`, `postPrCommentIfNeeded(job)` for the
       PR case, `deleteOldJobs(job)` for the completed/failed cases (parity with
       `completeJob` / the `failed` switch arm today).
     - `zero_pending_reconciliations_total{outcome = target}`++.
     - register an `afterCommit` synchronization that deletes the Quartz trigger
       (`scheduler.deleteJob(PREFIX_JOB_CONTEXT + jobId)`) and calls
       `wakeNextDispatchableJob()`. Trigger removal only after the status commit
       (invariant 5 / spec "remove any Quartz trigger only after the status
       transition commits").
6. `NoRecordFoundException` / `ObjectAlreadyExistsException` / concurrent trigger
   deletion during step 5 → `quartz_trigger_races_total`++, log at info, proceed
   from durable job state (the status transition already committed or will on the
   next call).

Idempotency: a second call after a successful transition hits steps 1–2, sees the
now-terminal status via the deriver's rule 1, returns `ALREADY_TERMINAL` with no
event and no trigger action.

Cross-replica safety: callers that already hold the Redis `job-execution-lock:`
(the `ScheduleJob` paths) keep holding it across the call. The sweep and the
recovery endpoint acquire the same lock per job id around their `reconcile` call
(`SETNX` with the existing TTL, fail-closed on Redis error = skip this job this
pass). Combined with the DB row lock this gives single-transition, single-event
behaviour under two replicas.

### 3.5 Scheduler wiring (`ScheduleJob`)

- `executeApprovedJobs`: when `flow` is absent, call
  `jobReconciliationService.reconcile(job.getId(), false)`. Return `true`
  (deschedule) on `APPLIED` / `ALREADY_TERMINAL`; return `false` (keep the
  existing 30s trigger — no create/delete, just its normal cadence) on
  `HELD_ANOMALY` / `SKIPPED_HAS_WORK` / `RACE`, so a transient state is retried
  while the admission guard (§3.6) keeps it from blocking others.
- `executePendingJob`: replace the `else { completeJob(job); deleteOldJobs(job); }`
  branch with the same `reconcile` call and the same return handling.
  `completeJob` is deleted; its body moves into `JobReconciliationService` (the
  `COMPLETED` transition).
- **Behaviour change, intentional:** the `pending` empty-flow path today calls
  `completeJob` unconditionally. Under this design a `pending` job with 0 pending
  steps but inconsistent step evidence (e.g. a `failed` step) now derives `FAILED`
  or `HELD_ANOMALY` instead of being silently marked `completed`. This is the
  point of invariant 2/3.
- The `queue` / `running` / `default` switch arms are unchanged. A `running` job
  with 0 pending steps is still handled by the sweep's heartbeat path, not by
  reconciliation (the executor owns it).

### 3.6 Executor admission guard (`JobRepository`)

Zombie signature: `status ∈ {pending, approved}` **and** ≥1 step exists **and**
no step is `pending`. Everything else keeps blocking:

- `queue` / `running` → executor owns it (a step may be `running` with 0 pending
  steps mid-apply — must still block its workspace successors).
- `waitingApproval` → user owns it.
- `pending` / `approved` with **no steps yet** → not initialised; still blocks
  (steps are created lazily on the first Quartz fire by
  `TclService.initJobConfiguration`).

`isJobNextInDispatchOrder` — the `earlier.status IN ('pending','approved')` clause
gains:

```sql
AND ( NOT EXISTS (SELECT 1 FROM step s WHERE s.job_id = earlier.id)
      OR EXISTS (SELECT 1 FROM step s WHERE s.job_id = earlier.id
                                        AND s.status = 'pending') )
```

`findNextDispatchableJobId` — the same predicate is added, but only to rows whose
`status IN ('pending','approved')`; the per-workspace `earlier.status NOT IN
(terminal)` blocker subquery keeps `queue`/`running`/`waitingApproval` always
blocking.

Toggle: `admissionGuardEnabled` (default `true`) selects between the guarded and
unguarded query methods in the repository call sites
(`ScheduleJob.isNextInDispatchOrder`, `wakeNextDispatchableJob`,
`ExecutorAvailabilityListener`). The SQL itself is not branched.

Ordering / serialization preserved: the guard only *removes* zombie rows from the
"earlier blocker" set; it never reorders eligible jobs and never lets a later job
pass an earlier job that has a `pending` step or is uninitialised.

### 3.7 Sweep wiring (`JobReconciliationSweep`)

Per active job, in id order:

1. If `sweep-enabled` and `job` has ≥1 step and 0 pending steps and status is
   non-terminal: acquire the per-job Redis lock; if acquired,
   `jobReconciliationService.reconcile(jobId, !properties.isAutoRemediate())`.
   `dryRun` (autoRemediate off) logs the intended transition and bumps
   `zero_pending_nonterminal_total` only. If `sweep-enabled` is off, skip to
   step 3.
2. Jobs with 0 steps → today's behaviour: `reconcileTrigger` only (let the normal
   trigger initialise them).
3. `reconcileTrigger` unchanged in logic; wrap the recreate call so a successful
   `createJobContext` bumps `quartz_trigger_recreated_total` and the
   `ObjectAlreadyExistsException` catch bumps `quartz_trigger_races_total`.
4. `failIfExecutorHeartbeatExpired` unchanged.

### 3.8 Recovery endpoint (`SchedulerReconciliationController`)

`/admin/v1/scheduler/reconciliation`, secured the same way as other
super-service-only operations (`user is a super service`), reachable in-cluster.

- `GET` — no writes. Returns a JSON list of non-terminal jobs with 0 pending
  executable steps: `{ jobId, workspaceId, currentStatus, derivedTarget,
  stepEvidence: [{stepNumber, status}], queueHeadPosition, ageSeconds }`.
  `derivedTarget` comes from `deriver.derive` (dry). `queueHeadPosition` is the
  1-based index of the job among dispatch-eligible jobs ordered by id, or `null`
  when the job is not itself dispatch-eligible. Also returns the current queue
  head (`jobId`, `status`, `nextStepState`, `ageSeconds`) and whether all
  executors report idle.
- `POST` with body `{ "confirm": true, "jobIds": [..] | "all-deterministic" }` —
  calls `reconcile(jobId, false)` only for jobs whose derived target is
  `FAILED` / `CANCELLED` / `REJECTED` / `COMPLETED`. `ANOMALY` /
  `RETAIN_WAITING_APPROVAL` are reported back as skipped, never transitioned.
  Every applied transition writes an audit log line
  (`job id`, `from`, `to`, `actor`, `evidence`). After the batch, calls
  `wakeNextDispatchableJob()` once.
- No credentials or variable values are logged anywhere in the controller.

### 3.9 Metrics

Micrometer, dot-named to match the repo convention (`quartz.jobs.executing`,
`executor.availability.age.seconds`); Prometheus renders them with underscores as
the spec names them.

Counters (in `JobReconciliationService`, registered via `MeterRegistry`):

| Dot name | Prometheus | Labels |
|---|---|---|
| `terrakube.scheduler.zero.pending.nonterminal` | `terrakube_scheduler_zero_pending_nonterminal_total` | `status` |
| `terrakube.scheduler.zero.pending.reconciliations` | `terrakube_scheduler_zero_pending_reconciliations_total` | `outcome` |
| `terrakube.scheduler.reconciliation.anomalies` | `terrakube_scheduler_reconciliation_anomalies_total` | — |
| `terrakube.scheduler.quartz.trigger.recreated` | `terrakube_scheduler_quartz_trigger_recreated_total` | — |
| `terrakube.scheduler.quartz.trigger.races` | `terrakube_scheduler_quartz_trigger_races_total` | — |

Gauges (in `SchedulerQueueMetrics`, polling `JobRepository`):

| Dot name | Prometheus | Meaning |
|---|---|---|
| `terrakube.scheduler.executor.queue.depth` | `terrakube_scheduler_executor_queue_depth` | count of dispatch-eligible jobs (guarded query) |
| `terrakube.scheduler.executor.queue.head.age.seconds` | `terrakube_scheduler_executor_queue_head_age_seconds` | `now - createdDate` of the eligible head, `0` if empty |
| `terrakube.scheduler.executor.queue.head.job` | `terrakube_scheduler_executor_queue_head_job` | numeric job id of the eligible head as the gauge **value** (not a label), `-1` if empty |

Alert rules — documented in the spec/ops notes, **not** deployed by this change:

- non-terminal zero-pending job present for more than `anomalyGraceSeconds`;
- `executor_queue_head_age_seconds` rising while `quartz.jobs.executing` ≈ 0 and
  no executor heartbeats are expiring (idle executors + blocked queue).

### 3.10 Rollout flags — `io.terrakube.api.scheduler.reconciliation.*`

| Property | Default | Effect |
|---|---|---|
| `sweep-enabled` | `true` | Master switch for the **sweep** calling `reconcile` and for the endpoint `POST` applying transitions. Off → the sweep only does trigger/heartbeat reconciliation as it does today; the endpoint `GET` report still works. Does **not** affect the scheduler inline path. |
| `auto-remediate` | `true` | When `sweep-enabled`, the sweep applies deterministic `completed`/`failed`/`cancelled`/`rejected` transitions. Off → the sweep runs `reconcile` in dry-run: metrics + logs only. Anomalies are always held regardless. |
| `admission-guard-enabled` | `true` | Selects the guarded admission queries (§3.6). |
| `anomaly-grace-seconds` | `300` | Age threshold for the anomaly alert / the endpoint's "stale" flag. |

The **scheduler inline path** (`executeApprovedJobs` / `executePendingJob` calling
`reconcile` on an empty flow) is **always active** and not flag-gated: it replaces
existing code (`completeJob` / the missing `executeApprovedJobs` branch) and the
old behaviour it replaces is either identical (the `pending` completed case) or
the bug itself (the `approved` case). The flags gate only proactive sweep
remediation of pre-existing zombies and the admission-query change.

Recommended phased sequence for a cautious environment:
1. deploy with `auto-remediate=false`, `admission-guard-enabled=false`
   (`sweep-enabled=true`) — observe `zero_pending_nonterminal_total`, sweep
   dry-run logs, the `GET` report;
2. set `admission-guard-enabled=true` — idle executors stop being blocked by
   existing zombies even before they are transitioned;
3. set `auto-remediate=true` — zombies are cleaned up automatically;
4. validate a burst run across ≥2 API replicas and multiple executor pods.

## 4. Test plan

### 4.1 Unit — `JobTerminalStateDeriverTest`
- all-`completed` steps, `job.approved` → `COMPLETED`;
- single plan step `completed` + `job.planChanges=false` → `COMPLETED` (no-change);
- last step `failed` → `FAILED`, never `COMPLETED`;
- a `cancelled` step → `CANCELLED`; a `rejected` step / `job.rejected` → `REJECTED`;
- `job.waitingApproval`, 0 pending steps → `RETAIN_WAITING_APPROVAL`;
- 0 steps, or a pending step with no matching flow, or mixed
  `pending`+`failed` → `ANOMALY`;
- already-terminal job → `ALREADY_TERMINAL`.

### 4.2 Repository integration — extend `JobDispatchOrderRepositoryIntegrationTest`
- earlier `approved` job with all steps `completed` does **not** block a later
  `pending` job with a `pending` step;
- earlier `pending` job with a `pending` step still blocks;
- earlier `pending` job with **no steps** still blocks;
- earlier `running` job with its step `running` (0 pending steps) still blocks its
  workspace successors;
- FIFO order among genuinely eligible jobs is unchanged;
- guarded vs unguarded query selection via `admissionGuardEnabled`.

### 4.3 Service integration — `JobReconciliationServiceIntegrationTest`
- `approved` + all steps `completed` → one transition to `completed`, one
  `JobStatusEvent`, workspace `lastJobStatus` updated, Quartz trigger removed,
  `findNextDispatchableJobId` then returns the next job;
- repeat call → `ALREADY_TERMINAL`, no second event, no second trigger delete;
- dry-run → no transition, `ReconciliationResult` names the intended target;
- `ANOMALY` job → no transition, `reconciliation_anomalies_total` incremented,
  job still listed by the `GET` report, excluded from `findNextDispatchableJobId`.

### 4.4 Concurrency — `JobReconciliationConcurrencyIntegrationTest`
- two threads call `reconcile` on the same zombie → exactly one transition, one
  event (DB row lock + idempotent deriver);
- sweep + a `ScheduleJob` fire racing on the same job → no duplicate dispatch, no
  duplicate notification;
- simulate `ObjectAlreadyExistsException` on trigger recreate →
  `quartz_trigger_races_total` incremented, no job left blocking.

### 4.5 `ScheduleJobTest`
- `executeApprovedJobs` with `getNextFlow` empty → delegates to
  `JobReconciliationService` (mock) with `dryRun=false`, deschedules on a terminal
  result;
- `executePendingJob` empty-flow branch → same delegation; `completeJob` removal
  does not regress the VCS / PR-comment / `deleteOldJobs` side effects (now
  asserted on the service).

### 4.6 End-to-end burst
Submit 20 workspace jobs across ≥2 API replicas; inject a `755`-style stale
`approved` job with all steps `completed` ahead of the newest; assert: the stale
job is reconciled to `completed` exactly once, its trigger is gone, and the
remaining jobs dispatch to the (otherwise idle) executor pods without manual
trigger surgery.

## 5. Acceptance criteria mapping

| Spec criterion | Covered by |
|---|---|
| 1 approved+all-success → `completed` once, unblocks FIFO | §3.3 rule 6, §3.4, §4.3, §4.6 |
| 2 no-change job resolves, does not block | §1.2, §3.3 rule 6, §4.1 |
| 3 failed final step → `failed`, never `completed` | §3.3 rule 2, §4.1 |
| 4 waiting-approval not dispatched, not a queue position | §3.3 rule 5, §3.6, §4.2 |
| 5 two replicas: no duplicate transition/notification/dispatch | §3.4 locking, §4.4 |
| 6 inject `755`-style rows ahead of `777`, next job dispatches | §3.6, §3.7, §4.6 |
| 7 undetermined target → visible + excluded, others proceed | §3.3 rule 7, §3.8, §4.3 |
| 8 Quartz races recoverable, no idle-executor deadlock | §3.4 step 7, §3.7, §4.4 |

## 6. Out of scope

- Changing how the executor reports step/plan outcomes.
- Introducing a `noChanges` transition (see §1.2).
- A persisted `scheduler_eligible` marker column (derive-in-query chosen instead).
- Deploying alert rules (documented only).
- CLI tooling (protected REST endpoint chosen instead).
