---
name: terrakube-structured-output
description: Comprehensive architecture, lifecycle, sensitivity protection, state resolution, asynchronous persistence queuing, atomic context patching, and API streaming reference for Terrakube Executor structured output (Plan, Apply & Destroy).
---

# Terrakube Structured Output Guide (Plan, Apply & Destroy)

Terrakube Executor processes Terraform and OpenTofu execution streams in real-time and post-hoc to generate structured JSON diffs, live status transitions, diagnostic trees, rendered diffs for pull request comments, and sanitized outputs for the Terrakube Web UI, API clients, and external integrations.

---

## 1. High-Level Architectural Pipeline

```
                     +---------------------------------------+
                     |      Terraform / OpenTofu Process     |
                     |  (plan -json / apply -json / destroy) |
                     +-------------------+-------------------+
                                         |
                       Streams JSON lines over stdout
                                         v
                     +-------------------+-------------------+
                     |   TerraformJsonEventParser (Live)     |
                     |  - Emits resource & diagnostic events |
                     |  - Renders human console diff/logs    |
                     +-------------------+-------------------+
                                         |
                       Non-blocking snapshot handoff
                                         v
                     +-------------------+-------------------+
                     | StructuredOutputPersistenceQueue      |
                     |  - Coalesces by (jobId, stepId, phase)|
                     |  - Bounded memory (queueMaxBytes=64MB)|
                     |  - Delayed recovery background engine |
                     +-------------------+-------------------+
                                         |
                       Persister worker thread (async/sync)
                                         v
                     +-------------------+-------------------+
                     |       POST /context/v1/{jobId}/patch  |
                     |  - Monotonic revision: epoch * 1M + s |
                     |  - Atomic Redis lock (60s TTL)        |
                     |  - ContextPatchApplier (subsumption)  |
                     +---------+-------------------+---------+
                               |                   |
            API Reachable (200 OK)         API Unreachable / 5xx
                               |                   |
                               v                   v
                     +---------+---------+ +-------+-------------------------+
                     | Step ACKED        | | ContextSpoolService (Fallback)  |
                     | - Real-time SSE   | | - context-spool/<org>/<job>/... |
                     |   live update via | | - Bounded gzip (5MB / 15MB)     |
                     |   ProcessLogs     | | - Step marked SPOOLED           |
                     +-------------------+ +-------+-------------------------+
                                                   |
                                                   v
                                           +-------+-------------------------+
                                           | SpoolRecoveryService (Sweeper)  |
                                           | - Clustered DB lease fencing    |
                                           | - Dead-executor probing sweep   |
                                           | - Merges patch via write lock   |
                                           +---------------------------------+
```

---

## 2. Real-Time Event Parsing & Normalization (`TerraformJsonEventParser`)

The `TerraformJsonEventParser` translates machine-readable stdout JSON lines from both Terraform and OpenTofu into structured memory objects while maintaining full fidelity for human-readable console output.

### 1. Supported Event Types & Normalization

| Event `type` | Source | Target State / Action | Description |
|---|---|---|---|
| `apply_start` | Both | `status = "applying"` | Resource operation begun. |
| `apply_progress` | Both | `elapsedSeconds` updated | In-flight execution timer. |
| `apply_complete` | Both | `status = "applied"` | Resource operation succeeded. |
| `apply_errored` | Both | `status = "errored"` | Resource operation failed. |
| `diagnostic` | Both | `jobDiagnostics` or attached | Attached to specific resource by address or stored at job level; rendered into multiline console logs. |
| `provision_start` | Both | `currentProvisioner = name` | Tracks in-flight provisioner execution. |
| `provision_progress`| Both | Appends provisioner stdout | Captures live provisioner console logs. |
| `provision_complete`| Both | `currentProvisioner = null` | Provisioner finished successfully. |
| `provision_errored` | Both | `currentProvisioner = null` | Provisioner finished with error. |
| `refresh_start` | Both | `status = "refreshing"` | Resource state refresh initiated. |
| `refresh_complete` | Both | `status = previousStatus` | Resource state refresh completed. |
| `ephemeral_op_*` | Terraform | `ephemeral-opening`, `ephemeral-renewed`, `ephemeral-closing`, `ephemeral-errored` | Ephemeral resource lifecycle using `hook.action` (`open`, `renew`, `close`). |
| `ephemeral_action_*`| OpenTofu | Normalized to identical `ephemeral-*` statuses | OpenTofu lacks `hook.action` and emits `Msg` strings ("Opening...", "Renewing...", "Closing..."); parser normalizes both engines to the same vocabulary. |
| `planned_change` | Both | Populates `liveChanges` | Initial planned resource representation. |
| `resource_drift` | Both | Stored in `change.drift` | Records out-of-band state drift detected during refresh. |

### 2. Fast-Plan Immediate Flush Optimization
* `lastFlush` is initialized to `0` rather than `System.currentTimeMillis()`.
* **Guarantee**: The very first JSON line always passes the elapsed-time threshold and flushes immediately to the persistence queue.
* **Problem Prevented**: For short or fast plans that complete in under `APPLY_PROGRESS_FLUSH_INTERVAL_MS` (`1,000ms`), initializing to `now()` caused the UI to remain on console view for the entire plan duration and only flip to Structured Output after step completion.

### 3. Diagnostic Extraction & Multiline Splitting
* `diagnostic` events can be multiline blocks containing headers, code snippets, and explanations.
* The parser formats the diagnostic into a unified block for console logging and splits it across newlines (`acceptConsoleLines`), ensuring each line gets a unique sequence number in the raw step logs.

---

## 3. Plan Structured Output Lifecycle (`PlanStructuredOutputService`)

### 1. Live Streaming vs. Summary Reconciliation
1. **Live Stream Accumulation (`publishPlanProgress`)**:
   - Gathers `planned_change` lines during `terraform plan -json`.
   - Flushes incremental updates to `StructuredOutputPersistenceQueue` (throttled by `APPLY_PROGRESS_FLUSH_INTERVAL_MS`, `1,000ms`).
2. **Post-Plan Summary Reconciliation (`publishPlanSummary`)**:
   - Executes `terraform show -json <plan_file>` via `TerraformClient.showPlanJson()`.
   - Passes `terraformEnvironmentVariables` so backend authentication and dynamic cloud credentials remain valid during inspection.
   - Merges live streamed changes with the authoritative `show -json` representation using `mergeShowJsonDiff()`.

### 2. Plan-Only Output Changes (`output_changes`)
A Terraform plan can modify root module outputs (e.g., derived from data sources or expressions) without touching any managed resources.
* **Mechanism**: `buildOutputChangeEntries` reads `plan.output_changes` and appends entries to `changes` with:
  ```json
  {
    "address": "vpc_id",
    "resourceType": "output",
    "resourceName": "vpc_id",
    "action": "update",
    "isOutputChange": true,
    "before": "...",
    "after": "..."
  }
  ```
* **Reviewer Protection**: Prevents plans with zero resource modifications but altered outputs from rendering as empty no-ops (#3675).
* **Seeding Isolation**: `ApplyStructuredOutputService.seedFromPlan` explicitly skips entries where `isOutputChange == true` because `apply -json` emits no output events, preventing them from being stuck in `"pending"` status forever.

### 3. Affirmative No-Change Plan Marker (`noChangePlan`)
* A final snapshot checks `!hasPlanErrors(changes, jobDiagnostics) && changes.isEmpty()`.
* If true, affirmatively sets:
  ```json
  "noChangePlan": { "planStepId": "<stepId>" }
  ```
* If false or if error diagnostics exist, sets `"noChangePlan": null`.
* **UI Protection**: Signals to the UI that an upcoming apply step is an affirmative no-op rather than an unpersisted or corrupted run.

### 4. Bounded Rendered Plan Diff Streaming (`RenderedPlanText`)
Terraform's `-json` mode emits terse single-line summaries rather than the full human-readable diff. Terrakube reconstructs this diff post-hoc:
* **Execution**: Calls `terraform show <plan_file>` via `streamPlanAsHumanText()`.
* **Bounded Buffer (`RenderedPlanText`)**:
  - Streams lines directly to the console output while feeding a size-bounded collector.
  - Keeps up to `renderedPlanContextMaxBytes` (default: 48 KiB; API maximum: 1 MB via `MAX_RENDERED_PLAN_TEXT_BYTES`).
  - When truncated, counts omitted lines and bytes and appends an informative truncation note.
  - **Preserves Trailing Summary**: Specifically extracts and preserves the closing `Plan: X to add, Y to change, Z to destroy` or `No changes...` line so PR comments retain an accurate header.
* **Publishing**: Saved under context path `planRenderedText/<stepId>` for consumption by `PrCommentService` (#3769).

---

## 4. Apply & Destroy Structured Output Lifecycle (`ApplyStructuredOutputService`)

### 1. Seeding Rules (`seedFromPlan`)
Before `apply` begins, `seedFromPlan` clones `planStructuredOutput` for the step:
1. Marks all managed resources with initial status `"pending"` and `elapsedSeconds: 0`.
2. **Filters Output Changes**: Skips all entries with `isOutputChange: true`.
3. **Preserves Ephemeral Status**: Resources with `action: "ephemeral"` keep their plan-time status rather than reverting to `"pending"`, since ephemeral lifecycles occur during planning.

### 2. Automatic Import Unsticking
Config-driven `import` blocks do not emit `apply_start` or `apply_complete` hook events over `apply -json` in Terraform CLI.
* **Resolution**: During post-apply state resolution (`resolveFinalValues`), if a resource with `action: "import"` and status `"pending"` is confirmed present in post-apply state values, its status is automatically updated to `"applied"`.

### 3. Premature Termination & Eviction Reconciler (`finalizeResourceStatuses`)
When an apply or destroy run terminates prematurely (e.g. failure of another resource, user cancellation, SIGTERM pod eviction):
```
                      [Execution Terminated]
                                |
             +------------------+------------------+
             |                                     |
    [Interrupted / SIGTERM]               [Failed Execution]
             |                                     |
             v                                     v
  In-progress resources:                In-progress resources:
  status -> "interrupted"               status -> "errored"
  (clears previousStatus)                          |
             |                                     |
             +------------------+------------------+
                                |
                                v
                       Pending resources:
                     status -> "not-started"
```
* **State Resolution Guard**: `resolveFinalValues` is skipped entirely if the execution was interrupted or shutting down, preventing corrupted partial states from being published.

### 4. Post-Apply State Resolution (`resolveFinalValues`)
Resolves unknown computed values (`afterUnknown: true`) against the post-apply state:
1. Reads `state.json` via `terraform show -json`.
2. Recursively traverses `root_module` and `child_modules` via `collectResourceValues()`, gathering both `values` and `sensitive_values`.
3. Merges plan sensitivity with state sensitivity via `mergeSensitiveMetadata()`.
4. Executes `resolveUnknownRecursive()`:
   - **Container Guard**: If the container node is `Boolean.TRUE`, immediately halts recursion and keeps `after` as `null`.
   - **Leaf Splicing**: For `afterUnknown: true` leaves, splices the real state value into `after`, runs `sanitizeSensitiveValues()`, and flips `afterUnknown` to `false`.

### 5. Destroy Structured Output Workflow (`runJsonDestroy`)
* Runs `terraform destroy -json`.
* Does not seed from a prior plan step; populates `changes` directly from the event stream.
* Publishes under the `"apply"` phase with all-delete actions, enabling the UI to render the destroy run using the standard execution view.

---

## 5. Asynchronous Bounded Persistence Queue (`StructuredOutputPersistenceQueue`)

To guarantee that slow network operations, API downtime, or S3 latency never block or fail Terraform reader threads, structured output publishing is decoupled via a bounded asynchronous queue.

### 1. Queue Architecture & Workers
* **Handoff**: Reader threads call `submit(StructuredSnapshot)` which performs a CPU-only deep copy and enqueues the snapshot without blocking.
* **Worker Thread**: A single daemon thread (`structured-output-persistence`) drains the queue, executes retries with backoff, and publishes patches.
* **Delayed Recovery Scheduler**: A scheduled single-thread executor (`structured-output-recovery`) retries snapshots that exhausted their normal retry budget.

### 2. Tuning Knobs (`StructuredOutputProperties`)

| Property | Default Value | Description |
|---|---|---|
| `queueCapacity` | `256` | Maximum number of snapshots allowed in the pending queue. |
| `queueMaxBytes` | `67,108,864` (64 MB) | Estimated byte limit for all pending snapshots. |
| `maxPersistAttempts` | `4` | Immediate retry attempts on the queue worker. |
| `initialBackoffMs` | `250 ms` | Initial exponential backoff delay. |
| `maxBackoffMs` | `4000 ms` | Maximum exponential backoff delay. |
| `drainTimeoutMs` | `30000 ms` (30s) | Maximum time `awaitDrain` waits before step completion. |
| `recoveryRetentionMs` | `600,000 ms` (10m) | Retention window for delayed recovery retries. |
| `recoveryInitialDelayMs`| `15,000 ms` (15s) | Initial delay before first delayed recovery attempt. |
| `recoveryMaxDelayMs` | `60,000 ms` (60s) | Maximum interval between delayed recovery attempts. |
| `recoveryMaxRetained` | `64` | Maximum snapshots retained for delayed recovery. |
| `recoveryMaxRetainedBytes`| `67,108,864` (64 MB) | Memory byte budget for retained recovery snapshots. |

### 3. Coalescing & Eviction Hierarchy
* **Coalescing**: Keyed by `(jobId, stepId, phase)`. If a newer snapshot arrives for the same key, it replaces the existing pending snapshot in-place.
* **Eviction Ranking (`evictionRank`)**:
  - Non-final progress snapshots are evicted before final snapshots (`rank += 2`).
  - Snapshots belonging to the same job/step as the incoming snapshot are preferred for eviction (`rank += 1`).
  - **Single Snapshot Invariant**: A single incoming snapshot is always accepted regardless of size, ensuring the final output of a step is never rejected.

### 4. Step Lifecycle Drain Contract (`awaitDrain`)
At the end of a step (in `plan()`, `apply()`, or `destroy()`), the executor invokes:
```java
structuredOutputPersistenceQueue.awaitDrain(Duration.ofMillis(drainTimeoutMs));
```
This ensures all in-flight writes are flushed to the API before the pod terminates or reports the step as finished.

---

## 6. Server-Side Atomic Patching, Monotonic Revisions & Distributed Locking

### 1. Patch API Specification (`POST /context/v1/{jobId}/patch`)
Replaces whole-document GET-merge-POST operations with path-level atomic patching.
* **Request Body**:
  ```json
  {
    "revision": 1729000000000001,
    "set": {
      "planStructuredOutput/step-uuid": [ ... ],
      "terrakubeUI/step-uuid": "<div data-terrakube-structured-plan=\"true\"></div>",
      "structuredOutputStatus": {
        "state": "PERSISTED",
        "updatedAtEpochMs": 1729000000000,
        "stepId": "step-uuid",
        "phase": "plan",
        "finalSnapshot": true
      },
      "noChangePlan": { "planStepId": "step-uuid" }
    }
  }
  ```
* **Deletion**: Passing `null` as the value deletes the entry while retaining a tombstone revision.
* **Limits**: Maximum 64 entries per patch; maximum 2 path segments (e.g., `key/child`); paths cannot start with `contextRevisions`.

### 2. Monotonic Revision Scheme (`ContextRevision`)
Revisions are 64-bit integers encoded as:
$$\text{revision} = \text{executionEpoch} \times 1,000,000 + \text{seq}$$
* **`executionEpoch`**: API server's clock in milliseconds stamped at step dispatch (`ExecutorContext.executionEpoch`).
  - Ensures any re-dispatched execution automatically outranks all writes from prior executions.
  - Zombie executors from earlier executions are rejected with `PatchResult.SKIPPED`.
* **`seq`**: Monotonic counter per job within the current execution.

### 3. Subsumption & Revisions Map (`ContextPatchApplier`)
The API tracks applied revisions inside a top-level `contextRevisions` JSON object:
* An incoming patch entry is applied **only if** `incomingRevision > storedRevision`.
* **Parent Subsumption**: Writing to a parent key (e.g. `planStructuredOutput`) checks against and subsumes all child keys (e.g. `planStructuredOutput/step-1`), removing superseded child revision entries.
* **Legacy Replace Protection (`protectRevisions`)**: If a legacy client performs a full `POST /context/v1/{jobId}` replace, `protectRevisions()` preserves all stored revisioned fields, preventing accidental clobbering.

### 4. Distributed Redis Write Locking (`ContextWriteLock`)
* **Key**: `job-context-write-lock:<jobId>` (`KEY_PREFIX = "job-context-write-lock:"`)
* **Properties**: 60s TTL, 5s acquire timeout, token compare-and-delete release.
* **Zero Database Transactions**: No database transaction or table row lock is held during cloud storage reads or writes. If the lock cannot be acquired within 5s, the API fails closed with HTTP 503 (`Retry-After: 5`).

---

## 7. Sensitivity Protection & Sanitization Matrix

| Scenario | Plan Metadata (`afterSensitive`) | State (`sensitive_values`) | Terrakube Resolution Action | Result in `after` |
|---|---|---|---|---|
| **Simple Leaf Secret** | `password = true` | `password = true` | Leaf unknown skipped / redacted | `password: null` |
| **Container Sensitivity** | `credentials = true` | `credentials = true` | Early return on container node | `credentials: null` |
| **Unknown Leaf -> Secret Object** | `token = { secret: true }` | `token = { secret: true }` | `sanitizeSensitiveValues` redacts nested keys | `token: { public: "x", secret: null }` |
| **Dynamic Mirror (`terraform_data`)** | `input = true, output = {}` | `input = true` | `normalizeResourceSensitivities` copies `input` -> `output` | `input: null, output: null` |
| **Terraform Outputs (`terraformOutputs`)** | N/A | `sensitive = true` | Redacted at source in `TerraformOutputsService` | `value: null` |

### Server-Side Double Protection (`ContextSanitizer`)
All context returned via `GET /context/v1/{jobId}` or streamed via `/context/v1/{jobId}/stream` passes through `ContextSanitizer`:
* Recursively redacts `planStructuredOutput`, `applyStructuredOutput`, and `terraformOutputs`.
* Acts as a zero-trust backstop ensuring unredacted secrets never reach the browser or API clients.

---

## 8. UI Availability Contract & Real-Time Replay (`contextAvailability.ts`)

The Terrakube Web UI interprets `/context/v1/{jobId}` using a strict three-state availability contract:

```
                  GET /context/v1/{jobId}
                            |
           +----------------+----------------+
           |                                 |
      HTTP 200 OK                  HTTP 503 / Network Error
           |                                 |
   Inspect structuredOutputStatus            v
           |                            UNAVAILABLE
    +------+------+                 (Show banner, trigger
    |             |                  auto-retry with backoff,
    v             v                  never infer "No changes")
 PERSISTED     PENDING
 (Render UI)   (Persistence in-flight;
                render loading skeleton,
                never infer "No changes")
```

### 1. State Semantics
* **`persisted`**: The context has been safely written. An empty changes array here is authoritative evidence of **"No changes"**.
* **`pending`**: Initial context state (`{"structuredOutputStatus":{"state":"PENDING"}}`). Persistence is catching up; the UI waits for SSE or polls again.
* **`unavailable`**: Storage is temporarily unreachable (`503 Service Unavailable` with `Retry-After`). The UI preserves existing views and schedules retries.

### 2. Real-Time Replay via SSE (`/stream`)
* UI connects to `GET /context/v1/{jobId}/stream`.
* Supports reconnection via `Last-Event-ID` header containing the Redis stream `RecordId` (falling back to `0-0` on unparseable or `*` IDs).
* `StreamingService.streamJobContext` and `ContextController.parseResumeId` emit `event: gap` (`reason: "trimmed"`, `recovery: "context"`) when trimmed history is skipped and `event: end` (`reason: "rotate"` after `sse-max-stream-duration` 30m, or `"complete"`).
* `useStructuredOutputStream` / `useEventStream` deduplicate events via `compareStreamIds`, reconnect on `rotate`/`overflow` with 250ms + up to 1750ms jitter, and retry transport errors with 1s–30s exponential backoff.
* `DefaultStructuredSnapshotPersister` (as well as `PlanStructuredOutputService.publishFinalPlanSnapshotSynchronous` and `ApplyStructuredOutputService.publishFinalApplySynchronous`) triggers `processLogs.sendStructuredUpdate` after context persistence.

---

## 9. Durable Cloud Storage Spooling & Sweeper Recovery

### 1. Zero Infrastructure Rerun Invariant
Terraform or OpenTofu must **never** be re-executed to regenerate lost UI diffs or diagnostics. If the API is unreachable when a step finishes:
1. Executor durably spools the patch to object storage:
   `context-spool/<orgId>/<jobId>/<stepId>-<phase>.json.gz`
2. Executor marks the step `delivery_state = SPOOLED` (with fallback to unsent if API is down).
3. The executor pod terminates safely without losing output.

### 2. Step `DeliveryState` Lifecycle
* **`NOT_DURABLE`**: Default initial state while the step is executing.
* **`ACKED`**: Synchronously delivered to the API with HTTP 200 OK.
* **`SPOOLED`**: Durably saved as a compressed gzip patch in cloud storage.
* **`RECOVERED`**: Merged into `context.json` by background sweeper or heartbeat probe.
* **`EXPIRED`**: Exceeded retention window (24h) or contained corrupted payload; deleted.

### 3. CWE-409 Decompression Limits
Enforced by `SpoolCompressionUtil`:
* Maximum compressed bytes: **5 MB**
* Maximum uncompressed bytes: **15 MB** (decompression bomb protection)

### 4. Clustered Sweeper & Dead-Executor Recovery
* **`SpoolRecoveryService`**: Runs every 30 seconds, claims batches of `SPOOLED` steps via atomic DB leases (`stepRepository.acquireLease`), validates the cryptographic envelope, and applies the patch via `ContextWriteService.patchRecovery()`.
* **Dead-Executor Heartbeat Probe (`JobReconciliationSweep`)**: When an executor heartbeat expires in Redis, probes storage for residual `context-spool/` patches, applies them, and marks the job failed.

---

## 10. Verification, Testing & Operational Telemetry

### 1. Automated Test Suites

#### Running Executor Tests
```bash
mvn test -Dtest=PlanStructuredOutputServiceTest,ApplyStructuredOutputServiceTest,StructuredOutputPersistenceQueueTest,DefaultStructuredSnapshotPersisterTest,JobContextServiceTest,ContextRevisionTest,RenderedPlanTextTest,TerraformOutputsServiceTest,TerraformSensitivitySanitizerTest,TerraformJsonEventParserTest,StructuredOutputResilienceIntegrationTest,StructuredOutputWiringTest,StructuredOutputSpoolFallbackTest -pl executor
```

#### Running API Tests
```bash
mvn test -Dtest=ContextControllerTest,ContextPatchApplierTest,ContextWriteServiceTest,ContextWriteLockRedisIntegrationTest,ContextPatchIntegrationTest,ContextSanitizerTest,ContextReadServiceTest,SpoolRecoveryServiceTest,StructuredOutputSpoolRecoveryIntegrationTest,StructuredOutputSpoolRecoveryNonTransactionalIntegrationTest,StepRepositoryLeaseTest -pl api
```

#### Running UI Tests
```bash
cd ui && CI=true yarn test --watchAll=false \
  src/domain/Jobs/__tests__/StructuredPlanOutput.test.tsx \
  src/domain/Jobs/__tests__/contextAvailability.test.ts \
  src/domain/Jobs/__tests__/collectDiagnostics.test.ts \
  src/hooks/__tests__/useStructuredOutputStream.test.ts
```

### 2. Operational Prometheus Metrics

| Metric Name | Type | Description | Tags / Labels |
|---|---|---|---|
| `terrakube.executor.structured.output.queue.depth` | Gauge | Snapshots waiting in persistence queue | - |
| `terrakube.executor.structured.output.queue.bytes` | Gauge | Estimated bytes of queued snapshots | - |
| `terrakube.executor.structured.output.recovery.pending` | Gauge | Snapshots held for delayed recovery | - |
| `terrakube.executor.structured.output.dropped` | Counter | Dropped snapshots | `reason`: `capacity`, `bytes`, `serialization` |
| `terrakube.executor.structured.output.persist` | Counter | Snapshot persist attempts | `outcome`: `success`, `failure` |
| `terrakube.executor.structured.output.persist.failures` | Counter | Persist failures by phase | `phase`: `plan`, `apply` |
| `terrakube.executor.structured.output.recovery.attempts` | Counter | Delayed recovery executions | - |
| `terrakube.executor.structured.output.recovery.expired` | Counter | Snapshots expired from delayed recovery | - |
| `terrakube.executor.structured.output.recovery.evictions` | Counter | Snapshots evicted from delayed recovery | - |
| `terrakube.spool.steps.recovered` | Counter | Spooled patches successfully recovered | `phase` |
| `terrakube.spool.steps.spooled` | Counter | Patches written to cloud storage spool | `phase` |
| `terrakube.spool.steps.expired` | Counter | Expired spooled patches purged | - |
| `terrakube.spool.corrupted` | Counter | Corrupted spools purged | - |
| `terrakube.spool.lease.lost` | Counter | DB lease lost during recovery | - |

### 3. Diagnostic Commands

#### Inspect Job Context JSON
```bash
curl -s -H "Authorization: Bearer <TOKEN>" \
  https://terrakube-api.localhost/context/v1/<JOB_ID> | jq .
```

#### Inspect Live SSE Stream
```bash
curl -N -H "Authorization: Bearer <TOKEN>" \
  -H "Accept: text/event-stream" \
  https://terrakube-api.localhost/context/v1/<JOB_ID>/stream
```

#### Inspect Active Context Write Lock in Redis
```bash
redis-cli GET "job-context-write-lock:<JOB_ID>"
```
