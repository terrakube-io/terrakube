---
name: terrakube-run-triggers
description: Architecture, domain model, durable outbox event delivery, static cycle detection, pessimistic row locking, fan-out idempotency, state-change qualification, and UI/Terraform provider conventions for Terrakube Run Triggers and Workspace Dependency Graphs.
---

# Terrakube Workspace Run Triggers & Dependency Graph Guide

This skill documents the complete production architecture, database schema, security checks, graph cycle detection, durable event outbox, reactive dispatch engine, UI conventions, and Terraform provider resource for Terrakube Run Triggers (`io.terrakube.api.rs.workspace.trigger` and `io.terrakube.api.plugin.scheduler.trigger`).

---

## 1. High-Level Architecture & Lifecycle

Run triggers allow downstream workspaces to automatically enqueue runs when an upstream workspace successfully applies infrastructure state changes. Workspaces declare dependencies on upstream source workspaces, creating a decentralized, composable Directed Acyclic Graph (DAG) without requiring monolithic pipeline definitions.

Terrakube implements a **durable transactional outbox pattern** (`run_trigger_event`) to guarantee at-least-once, crash-resilient trigger dispatching without risk of losing trigger executions if an API instance crashes or restarts.

```
Upstream Job (Workspace A) completes execution
       |
       v
JobReconciliationService.reconcile() [@Transactional]
       |  (Target: JobStatus.completed)
       |
       +---> 1. Update job status = 'completed'
       |
       +---> 2. RunTriggerEventWriter.enqueueIfQualifying(job)
       |        - Checks properties.isEnabled()
       |        - Checks workspaceRunTriggerRepository.existsBySourceWorkspaceIdAndEnabledTrue...
       |        - Checks runTriggerEventRepository.existsByJob_Id(...)
       |        - Inserts RunTriggerEvent(status = PENDING) in SAME transaction
       |
       +---> 3. deleteTriggerAfterCommit(jobId) [Quartz trigger cleanup]
       |
       v [Transaction COMMITTED atomically]

===================================================================================
Asynchronous Durable Event Poller & Dispatch Engine
===================================================================================
RunTriggerEventPollerScheduler (Quartz Cron: "*/10 * * ? * *")
       |
       v
RunTriggerEventPollerJob.execute()
       |
       +---> Phase 1: Reclaim stuck PROCESSING rows older than lease (default 60s)
       |              - RunTriggerEventTransactions.sweepStuckProcessingRows()
       |              - Reverts to PENDING (or FAILED if attemptCount >= maxAttempts)
       |
       +---> Phase 2: Claim due PENDING events (nextAttemptAt <= now)
                      - Batch size: eventPollerBatchSize (default 200)
                      - For each event:
                           |
                           v
              RunTriggerEventDispatchService.process(eventId)
                           |
                           +-> RunTriggerEventTransactions.claim(eventId)
                           |   Atomic conditional UPDATE (PENDING -> PROCESSING, attempt++)
                           |
                           +-> RunTriggerDispatchService.dispatchInternal(jobId)
                           |   - 1. State change qualification (serial/md5 changed or customScripts)
                           |   - 2. Runtime hop limit (cascadeDepth < maxCascadeDepth)
                           |   - 3. Load enabled outbound triggers (destination, template)
                           |   - 4. For each destination workspace:
                           |        * Idempotency check: jobRepository.findFirstByWorkspaceAndTriggeredByJobId(...)
                           |          - If exists pending: retry Quartz scheduleJob (tolerates ObjectAlreadyExistsException)
                           |          - If exists non-pending: skip duplicate dispatch
                           |          - If absent:
                           |            -> RunTriggerJobWriter.persist() [@Transactional(REQUIRES_NEW)]
                           |            -> jobNotificationTrigger.notifyStatusChanged()
                           |            -> scheduleJobService.createJobContext() [Quartz]
                           |
                           +-> If success: recordResult(PROCESSED)
                           +-> If error (RunTriggerDispatchPartialFailureException / exception):
                               recordResult(PENDING, nextAttemptAt = now + backoffWithJitter)
                               or recordResult(FAILED) if attemptCount >= maxAttempts

===================================================================================
Daily Event Retention Sweep
===================================================================================
RunTriggerEventRetentionScheduler (Quartz Cron: "0 5 3 * * ?")
       |
       v
RunTriggerEventRetentionJob.execute()
       |
       +---> Prunes terminal rows (PROCESSED, FAILED) older than eventRetentionDays (default 90)
```

---

## 2. Data Model, Entities & Database Migrations

### 2.1 Entity: `WorkspaceRunTrigger` (`io.terrakube.api.rs.workspace.trigger.WorkspaceRunTrigger`)
* **Table**: `workspace_run_trigger`
* **Primary Key**: `UUID` (`varchar(36)`)
* **Associations**:
  - `sourceWorkspace`: Upstream workspace whose apply fires the trigger (`ManyToOne`, lazy, optional = false).
  - `destinationWorkspace`: Downstream workspace that gets triggered (`ManyToOne`, lazy, optional = false).
  - `template`: Optional override template (`ManyToOne`, lazy, optional = true). Defaults to destination workspace's `defaultTemplate`.
  - `organization`: Denormalized owner (`ManyToOne`, lazy, `@Exclude`, derived in `@PrePersist` / `@PreUpdate`).
* **Constraints**:
  - Unique constraint: `uk_run_trigger_source_dest` on `(source_workspace_id, destination_workspace_id)`.
  - Check constraint: `chk_run_trigger_no_self` (`source_workspace_id != destination_workspace_id`).
* **Foreign Key Cascade Policies**:
  - `fk_run_trigger_source_workspace`: `deleteCascade = true` (deleting source workspace automatically cleans up triggers).
  - `fk_run_trigger_destination_workspace`: `deleteCascade = false` (queries enforce soft-delete check `destinationWorkspace.deleted = false`).
  - `fk_run_trigger_template`: references `template(id)`.
  - `fk_run_trigger_organization`: references `organization(id)`.
* **Indexes**:
  - `idx_run_trigger_source_enabled` on `(source_workspace_id, enabled)` for fast dispatch lookups.
  - `idx_run_trigger_destination` on `destination_workspace_id` for UI inbound listings and cascade cleanup.
  - `idx_run_trigger_organization` on `organization_id` for organization-wide cycle validation.

### 2.2 Entity: `RunTriggerEvent` (`io.terrakube.api.rs.workspace.trigger.RunTriggerEvent`)
* **Table**: `run_trigger_event`
* **Primary Key**: `UUID` (`varchar(36)`)
* **Purpose**: Durable transactional outbox table recording that a completed job owes trigger evaluation. Not an Elide JSON:API resource (internal queue plumbing).
* **Columns**:
  - `job_id` (`BIGINT`, optional = false): Foreign key referencing `job(id)` with `onDelete = CASCADE` (`fk_run_trigger_event_job_id`).
  - Unique constraint: `uk_run_trigger_event_job_id` on `job_id` (enforces exactly one event per completed job).
  - `status` (`varchar(20)`): Enum `RunTriggerEventStatus` (`PENDING`, `PROCESSING`, `PROCESSED`, `FAILED`).
  - `attempt_count` (`int`, default 0): Number of claimed execution attempts.
  - `last_attempt_at` (`datetime`): Timestamp of the most recent claim. Used as an optimistic concurrency token to reject stale writes.
  - `next_attempt_at` (`datetime`, nullable): Timestamp for next scheduled attempt under exponential backoff. Null indicates immediately due.
  - `last_error` (`text`): Plain `@Column` mapped to PostgreSQL `text`, NEVER `@Lob`.
  - Audit fields: `created_date`, `created_by`, `updated_date`, `updated_by` (`GenericAuditFields`).
* **Indexes**:
  - `idx_run_trigger_event_status_created_date` on `(status, created_date)` for poller queries (`findDueForProcessing`).
  - `idx_run_trigger_event_status_last_attempt` on `(status, last_attempt_at)` for the stuck-row sweep.

### 2.3 Job Lineage Columns & Indexes (`Job.java`)
* `triggered_by_job_id` (`Integer`): ID of the upstream job that triggered this run. Guarded by `@CreatePermission(expression = "user is a super service")`.
* `cascade_depth` (`int`, default 0): Number of hops from the root triggering job.
* Composite Index:
  - `idx_job_workspace_triggered_by_job_id` on `job(workspace_id, triggered_by_job_id)`. Backs `findFirstByWorkspaceAndTriggeredByJobIdOrderByIdDesc` for fan-out idempotency checks.

### 2.4 History State Identity Indexes (`History.java`)
* `idx_history_workspace_job_reference` on `history(workspace_id, job_reference)`.
* `idx_history_workspace_created_date` on `history(workspace_id, created_date)`.
* Backs rapid retrieval of the current job's state history and the preceding baseline record for state change qualification.

---

## 3. Security & Permission Architecture

Terrakube implements a 3-tier RBAC model (Organization Team, Project Access, Workspace Access) evaluated via `WorkspaceTriggerPermissions`.

| Operation | Elide Permission Check | Rule Semantics |
|---|---|---|
| **CREATE** | `team manage workspace trigger` | Requires `canManage` on destination AND `canView` on source (`TeamManageWorkspaceTrigger`). Validates structural invariants before superuser checks. |
| **UPDATE** | `team manage workspace trigger` | `sourceWorkspace` and `destinationWorkspace` endpoints are immutable (`user is a superuser`). Only `enabled` and `template` may be patched. |
| **DELETE** | `team delete workspace trigger` | Requires `canManage` on destination **only** (`TeamDeleteWorkspaceTrigger`). Upstream permission is intentionally not required, so owners can always detach unwanted triggers even if upstream moves to a restricted project. |
| **READ** | `team view workspace trigger` | Open to any member of the owning organization (`TeamViewWorkspaceTrigger`). |

### Structural Invariant Checks
In `TeamManageWorkspaceTrigger.isStructurallyValid()`, invariants are verified **before** the superuser shortcut to prevent malformed graph states:
1. Both `sourceWorkspace` and `destinationWorkspace` must be non-null.
2. Self-triggers are rejected: `!destination.getId().equals(source.getId())`.
3. Source and destination must belong to the exact same Organization.
4. If a custom `template` is provided, it must belong to the same Organization as the destination workspace.

> [!IMPORTANT]
> **Inverse Collections on Workspace**:
> `Workspace.runTriggers` and `Workspace.sourceRunTriggers` must use:
> `@UpdatePermission(expression = "team view workspace OR team project limited view workspace OR team limited view workspace")`
> because Elide maintains bidirectional links on trigger creation. A strict manage check here would cause ordinary users to fail creation with `UpdatePermission Denied`.
> Do **NOT** use `orphanRemoval = true` or `CascadeType.ALL` on these collections; deletion must only go through the `WorkspaceRunTrigger` endpoint.

> [!CAUTION]
> **Tenant Security & Field Exclusion**:
> The `organization` relationship field on `WorkspaceRunTrigger` is annotated with `@Exclude` to prevent API callers from spoofing ownership. The organization is derived automatically from the destination workspace via JPA `@PrePersist` / `@PreUpdate`.

---

## 4. Static Cycle Detection, Locking & Graph Validation

Implemented in `WorkspaceGraphValidationService.java` and triggered by `WorkspaceRunTriggerHook` at `LifeCycleHookBinding.TransactionPhase.PRECOMMIT`:

### 4.1 Organization Row Lock (`validateAcyclic`)
To prevent concurrent race conditions where two complementary edges are added simultaneously in different transactions and together close a cycle that neither transaction saw:
1. `validateAcyclic` locks the organization row: `organizationRepository.lockForUpdate(organizationId)`.
2. Fails closed with `OrganizationUnresolvableException` if the organization cannot be locked (e.g. disabled or deleted).
3. Plain database row locking (`SELECT ... FOR UPDATE`) is used rather than database-specific advisory locks, ensuring portability across all supported RDBMS engines (PostgreSQL, MySQL, SQL Server, Oracle).

### 4.2 Iterative DFS Cycle Detection
1. **Self-Trigger Check**: Rejects `sourceId.equals(destinationId)` with `CyclicDependencyException`.
2. **Graph Traversal**:
   - Queries `workspaceRunTriggerRepository.findEdgesByOrganizationId` returning lightweight `TriggerEdge` interface projections (never loading full entity trees).
   - Validates **both enabled and disabled** edges to prevent dormant cycle re-activation (e.g. defining A &rarr; B disabled and B &rarr; A enabled, then enabling A &rarr; B).
   - Excludes the current edge ID on updates so modified edges validate against their prospective shape.
   - Runs iterative DFS from `destinationId` to `sourceId` using an explicit stack and visited set (preventing stack overflows on deep graphs and infinite loops on existing cycles).
   - If a path exists, reconstructs and formats the loop in the error message (e.g. `workspace-1 -> workspace-2 -> workspace-1`).

### 4.3 Hook Selective Execution (`WorkspaceRunTriggerHook`)
- `topologyChangeApplies`: Executes cycle validation only on `CREATE`, or on `UPDATE` when `sourceWorkspace` or `destinationWorkspace` is repointed. Benign updates (`enabled`, `template`) bypass the organization lock and DFS search completely.
- `fanOutLimitApplies`: Checked on `CREATE`, or on `UPDATE` when `enabled` flips to `true` (`c.getModified() == true`) or `sourceWorkspace` changes.

### 4.4 Outbound Fan-Out Limit (`validateFanOutLimit`)
- Enforced at edge creation / enablement time, NOT at dispatch time.
- Locks the source workspace row: `workspaceRepository.lockForUpdate(sourceId)`.
- Counts enabled outbound edges: `countBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse(sourceId)`.
- If count exceeds `io.terrakube.run-trigger.max-outbound-triggers-per-workspace` (default 20), rejects with `FanOutLimitExceededException` (HTTP 400).
- If the limit is configured &le; 0, the fan-out limit is disabled.

---

## 5. Reactive Dispatch Engine & Durable Outbox Processing

### 5.1 Stage 1: Synchronous Outbox Write (`RunTriggerEventWriter`)
Invoked inside `JobReconciliationService.reconcile()` when `target == JobStatus.completed`:
```java
if (target == JobStatus.completed) {
    runTriggerEventWriter.enqueueIfQualifying(job);
}
```
- Operates inside the same transaction where the upstream job's status transition to `completed` commits.
- Performs lightweight qualification: checks `properties.isEnabled()`, confirms the source workspace has enabled outbound edges (`existsBySourceWorkspaceIdAndEnabledTrueAndDestinationWorkspace_DeletedFalse`), and verifies idempotency (`existsByJob_Id`).
- Inserts `RunTriggerEvent(status = PENDING)`. Either the completed job and the event commit together, or neither commits.

### 5.2 Stage 2: Asynchronous Event Poller (`RunTriggerEventPollerJob`)
Scheduled by `RunTriggerEventPollerScheduler` every 10 seconds (`*/10 * * ? * *`):
1. **Stuck-Row Sweep (`sweepStuckProcessingRows`)**:
   - Reclaims rows stuck in `PROCESSING` longer than `eventLeaseSeconds` (default 60s) due to crashed or killed API replicas.
   - Rows with `attemptCount < maxAttempts` revert to `PENDING`.
   - Rows reaching `eventMaxAttempts` transition to `FAILED`.
2. **Due Event Polling (`findDueForProcessing`)**:
   - Fetches up to `eventPollerBatchSize` (default 200) `PENDING` rows where `nextAttemptAt == null || nextAttemptAt <= now` ordered by `created_date`.
   - Hands each row to `RunTriggerEventDispatchService.process(eventId)`.

### 5.3 Concurrency & Transactional Claiming (`RunTriggerEventTransactions`)
- `claim(UUID eventId)`: Executes an atomic conditional UPDATE:
  ```sql
  UPDATE run_trigger_event
  SET status = 'PROCESSING', last_attempt_at = :now, attempt_count = attempt_count + 1
  WHERE id = :id AND status = 'PENDING'
  ```
  Only the worker whose UPDATE affects 1 row proceeds.
- `recordResult(UUID eventId, Date expectedLastAttemptAt, RunTriggerEventStatus newStatus, ...)`:
  - Validates `expectedLastAttemptAt`: if the stuck-row sweep reclaimed the row while processing was in-flight, `lastAttemptAt` will have moved forward and the stale write is discarded without overwriting a newer attempt.
- `handleFailure`:
  - On retryable failure, calculates exponential backoff with full jitter:
    `delay = rand(0, min(maxSeconds, initialSeconds * 2^attemptCount))`
  - Sets `status = PENDING` and `next_attempt_at = now + delay`.
  - Once `attemptCount >= maxAttempts`, marks `status = FAILED`.

### 5.4 State-Change Qualification (`changedState`)
Downstream triggers fire **only** when upstream work actually altered infrastructure:
1. **Flow Type Qualification**: A step with flow type `terraformApply`, `terraformDestroy`, or `customScripts` must have completed successfully. Plan-only runs never fire run triggers.
2. **Terraform State Identity Check**:
   - Queries `historyRepository.findFirstByWorkspaceAndJobReferenceOrderByCreatedDateDesc` for the state produced by the current job.
   - Queries `historyRepository.findFirstByWorkspaceAndCreatedDateLessThanOrderByCreatedDateDesc` for the baseline state prior to state write time.
   - Compares state identity:
     ```java
     boolean serialChanged = jobHistory.get().getSerial() > baseline.get().getSerial();
     boolean md5Changed = !Objects.equals(jobHistory.get().getMd5(), "0")
             && !Objects.equals(jobHistory.get().getMd5(), baseline.get().getMd5());
     boolean changed = serialChanged || md5Changed;
     ```
   - If serial did not increase and MD5 did not change (0 resources created/updated/deleted), downstream dispatch is skipped.
3. **Backward Compatibility Fallback**:
   - If history records are missing or legacy (`serial <= 1 && md5 == "0"`), fails open to `true` and increments `runTriggerDispatchMetrics.stateIdentityFallback()` (`terrakube.run.trigger.state.fallback`).

### 5.5 Fan-Out Deduplication & Reclaim Idempotency
When an event is reclaimed after a partial worker crash or lease expiry, `RunTriggerDispatchService.enqueue()` guarantees idempotency:
1. Checks for an existing job:
   `jobRepository.findFirstByWorkspaceAndTriggeredByJobIdOrderByIdDesc(destination, completedJob.getId())`
2. **Reconciliation**:
   - If an existing job is found in `pending` status: re-attempts Quartz scheduling (`scheduleJobService.createJobContext(existingJob)`), treating `ObjectAlreadyExistsException` as confirmation.
   - If an existing job is found in non-pending status: skips dispatch as already completed.
3. **New Trigger Execution**:
   - Resolves template: trigger override &rarr; destination workspace `defaultTemplate`.
   - Persists downstream job in an independent transaction via `RunTriggerJobWriter.persist(...)` (`@Transactional(propagation = Propagation.REQUIRES_NEW)`).
   - The job row commits before calling `scheduleJobService.createJobContext(savedJob)`, ensuring Quartz workers on separate connections can locate the row.
   - Dispatches notification: `jobNotificationTrigger.notifyStatusChanged(savedJob)`.
4. **Partial Failure Propagation**:
   - Per-dependent isolation catches exceptions per destination, but sets `partialFailure = true` and throws `RunTriggerDispatchPartialFailureException` at the end of the batch.
   - This causes `RunTriggerEventDispatchService` to back off and retry the remaining dependents, while already-persisted dependents are safely recognized and skipped on retry.

### 5.6 Runtime Hop Limit (`cascadeDepth`)
- Each downstream job records `cascadeDepth = sourceJob.getCascadeDepth() + 1`.
- If `nextDepth > properties.getMaxCascadeDepth()` (default 10), dispatch halts with a warning log, providing a runtime safety net against infinite cascade loops.

### 5.7 Retention Sweep (`RunTriggerEventRetentionJob`)
- Runs once daily at 03:05 (`0 5 3 * * ?`) via `RunTriggerEventRetentionScheduler`.
- Prunes terminal (`PROCESSED`, `FAILED`) rows older than `properties.getEventRetentionDays()` (default 90 days).

---

## 6. Frontend & Infrastructure as Code (IaC) Ecosystem

### 6.1 Frontend Conventions (`ui/src/domain/Workspaces/RunTriggers.tsx`)
1. **Combined Directional Fetch**:
   Queries inbound and outbound triggers in a single roundtrip using Elide's comma-separated OR syntax:
   ```typescript
   axiosInstance.get("runTrigger", {
     params: {
       include: "sourceWorkspace,destinationWorkspace,template",
       "filter[runTrigger]": `sourceWorkspace.id==${workspaceId},destinationWorkspace.id==${workspaceId}`,
     },
   });
   ```
2. **Bodyless DELETE**:
   When deleting triggers, omit the `Content-Type` header:
   ```typescript
   // DO NOT pass { headers: { "Content-Type": "application/vnd.api+json" } }
   axiosInstance.delete(`runTrigger/${id}`);
   ```
   Elide responds with HTTP 400 if a `Content-Type` header is supplied on a request without a body.
3. **Source Picker Filtering**:
   Excludes the current workspace (self-triggers forbidden) and workspaces already wired as incoming triggers (preventing 409 Conflict).
4. **Asymmetric Workspace Management**:
   - **Runs after (Incoming)**: Fully manageable (Add, Toggle Enabled, Delete) because the user has manage rights on the destination workspace.
   - **Triggers (Outgoing)**: Displayed as read-only informational entries with tags. Users are guided to manage them from the destination workspace.

### 6.2 Job Lineage Display (`ui/src/domain/Jobs/Details.tsx`)
Reads `job.data.attributes.triggeredByJobId`. When present, fetches the upstream job (`organization/${organizationId}/job/${upstreamJobId}?include=workspace`) and renders a prominent navigation banner linking back to the triggering run.

### 6.3 Terraform Provider (`terraform-provider-terrakube`)
Run triggers can be managed declaratively using the `terrakube_workspace_run_trigger` resource:
```terraform
resource "terrakube_workspace_run_trigger" "networking_to_app" {
  source_workspace_id      = terrakube_workspace_vcs.networking.id
  destination_workspace_id = terrakube_workspace_vcs.app.id
  template_id              = terrakube_organization_template.plan_apply.id
  enabled                  = true
}
```
* **Attributes**:
  - `source_workspace_id` (String, Required): Upstream workspace whose apply fires the trigger.
  - `destination_workspace_id` (String, Required): Downstream workspace that receives the triggered run.
  - `template_id` (String, Optional): Template ID override. Defaults to destination workspace default template.
  - `enabled` (Boolean, Optional, default `true`): Whether the trigger is active.
  - `id` (String, Read-Only): UUID of the trigger.

---

## 7. Key File References & Configuration Properties

### 7.1 Configuration Properties (`io.terrakube.run-trigger.*`)

| Property | Default | Type | Description |
|---|---|---|---|
| `enabled` | `true` | `boolean` | Global kill switch. When false, no downstream events or runs are created; edges remain configurable. |
| `max-cascade-depth` | `10` | `int` | Maximum trigger chain hop depth before cascade execution is blocked. |
| `max-outbound-triggers-per-workspace` | `20` | `int` | Maximum enabled outbound triggers per workspace, checked at creation/enablement time. |
| `max-dependents-per-apply` | `20` | `int` | `@Deprecated` alias for `max-outbound-triggers-per-workspace` kept for backward compatibility. |
| `event-worker-enabled` | `true` | `boolean` | Independent kill switch for the asynchronous event worker poller. When false, events accumulate in the outbox but are not claimed. |
| `event-max-attempts` | `10` | `int` | Maximum claimed execution attempts before a `RunTriggerEvent` is marked `FAILED`. |
| `event-lease-seconds` | `60` | `int` | Duration a claimed `PROCESSING` event may run before the stuck-row sweep reclaims it. |
| `event-backoff-initial-seconds` | `5` | `int` | Initial delay for the first retry before exponential backoff and jitter. |
| `event-backoff-max-seconds` | `900` | `int` | Ceiling delay between retries (15 minutes). |
| `event-poller-batch-size` | `200` | `int` | Maximum due events claimed by `RunTriggerEventPollerJob` in a single tick. |
| `event-retention-days` | `90` | `int` | Retention period for terminal (`PROCESSED`, `FAILED`) events before deletion by the retention sweep. |

### 7.2 Key Source File Reference

| Component | File Path | Role |
|---|---|---|
| `WorkspaceRunTrigger` | `api/src/main/java/io/api/rs/workspace/trigger/WorkspaceRunTrigger.java` | Elide entity with asymmetric permissions, immutable endpoints, and `@Exclude` organization derivation. |
| `RunTriggerEvent` | `api/src/main/java/io/api/rs/workspace/trigger/RunTriggerEvent.java` | JPA outbox entity storing pending, processing, processed, and failed trigger dispatch events. |
| `WorkspaceTriggerPermissions` | `api/src/main/java/io/api/rs/checks/trigger/WorkspaceTriggerPermissions.java` | 3-tier RBAC evaluation helper across organization, project, and workspace access levels. |
| `TeamManageWorkspaceTrigger` | `api/src/main/java/io/api/rs/checks/trigger/TeamManageWorkspaceTrigger.java` | Operation check for CREATE/UPDATE enforcing structural invariants and destination manage + source view rights. |
| `TeamDeleteWorkspaceTrigger` | `api/src/main/java/io/api/rs/checks/trigger/TeamDeleteWorkspaceTrigger.java` | Operation check for DELETE requiring destination manage rights only. |
| `TeamViewWorkspaceTrigger` | `api/src/main/java/io/api/rs/checks/trigger/TeamViewWorkspaceTrigger.java` | Operation check for READ open to organization members. |
| `WorkspaceRunTriggerHook` | `api/src/main/java/io/api/rs/hooks/trigger/WorkspaceRunTriggerHook.java` | Pre-commit lifecycle hook executing selective cycle detection and fan-out checking. |
| `WorkspaceGraphValidationService` | `api/src/main/java/io/api/plugin/scheduler/trigger/WorkspaceGraphValidationService.java` | Organization row-locking, iterative DFS cycle detection, and workspace fan-out bounds checking. |
| `RunTriggerEventWriter` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerEventWriter.java` | Synchronous enqueue of `RunTriggerEvent` inside `JobReconciliationService.reconcile()`'s transaction. |
| `RunTriggerEventTransactions` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerEventTransactions.java` | Dedicated Spring `@Transactional` proxy bean executing atomic conditional updates, sweeps, and prunes. |
| `RunTriggerEventDispatchService` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerEventDispatchService.java` | Drives event claiming, dispatch execution, backoff calculation, and terminal status recording. |
| `RunTriggerDispatchService` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerDispatchService.java` | State-change qualification, cascade depth checks, fan-out idempotency, and job enqueue orchestration. |
| `RunTriggerJobWriter` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerJobWriter.java` | `REQUIRES_NEW` isolated transaction boundary for downstream triggered job persistence. |
| `RunTriggerEventPollerJob` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerEventPollerJob.java` | Quartz job ticking every 10s to sweep stuck rows and dispatch due events. |
| `RunTriggerEventRetentionJob` | `api/src/main/java/io/api/plugin/scheduler/trigger/RunTriggerEventRetentionJob.java` | Daily Quartz job pruning expired terminal outbox rows. |
| `RunTriggers.tsx` | `ui/src/domain/Workspaces/RunTriggers.tsx` | Frontend React component for viewing, configuring, and toggling incoming/outgoing run triggers. |
| `workspace_run_trigger_resource.go` | `terraform-provider-terrakube/internal/provider/workspace_run_trigger_resource.go` | Terraform provider resource implementation for `terrakube_workspace_run_trigger`. |

---

## 8. Verification & Test Suite Reference

When extending or verifying run trigger capabilities, run the following automated test suites:

### Backend Unit & Integration Tests (`api`)
```bash
# Run trigger event outbox, dispatching, and lifecycle tests
mvn test -Dtest="WorkspaceRunTriggerTests,WorkspaceRunTriggerHookTest,RunTriggerPropertiesTest,RunTriggerDispatchServiceTest,RunTriggerEventBackoffTest,RunTriggerEventDispatchServiceTest,RunTriggerEventPollerJobTest,RunTriggerEventRetentionJobTest,RunTriggerEventTransactionsTest,RunTriggerEventWriterTest,RunTriggerJobWriterTest"

# Real database integration tests (outbox crash recovery, stuck sweep, state identity)
mvn test -Dtest="RunTriggerEventIntegrationTest,RunTriggerDispatchIntegrationTest"

# Concurrency and pessimistic locking tests
mvn test -Dtest="WorkspaceGraphLockConcurrencyTest,WorkspaceFanOutLockConcurrencyTest"
```

### Frontend Tests (`ui`)
```bash
cd ui && yarn test --watchAll=false src/domain/Workspaces/__tests__/RunTriggers.test.tsx
```

### Terraform Provider Tests (`terraform-provider-terrakube`)
```bash
cd terraform-provider-terrakube && go test -v ./internal/provider -run TestAccWorkspaceRunTrigger_basic
```
