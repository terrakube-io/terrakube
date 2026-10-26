# Structured output spool recovery and delivery contract

This document outlines the architecture, delivery lifecycle, upgrade ordering, and operational failure scenarios for Terrakube structured output recovery (Issue #3692). For what can still be lost and when output expires, see the [output delivery contract](output-delivery-contract.md).

---

## Architecture overview

```text
                      +-------------------+
                      |   Executor Pod    |
                      +-------------------+
                                |
                 Step Finishes (Plan/Apply/Destroy)
                                |
                 +--------------+---------------+
                 |                              |
         [1. Synchronous Delivery]       [API Outage / 5xx]
                 |                              |
                 v                              v
        POST /context/v1/{jobId}       [2. Cloud Storage Spool]
                 |                     context-spool/<org>/<job>/<step>-<phase>.json.gz
                 v                     (S3 / Azure Blob / GCS / Local)
        API Confirms (200 OK)                   |
        deliveryState = ACKED                   v
                                       deliveryState = SPOOLED
                                                |
                                       [Pod Terminates Safely]
                                                |
                                 +--------------+--------------+
                                 |                             |
                       [3. Periodic Sweeper]       [Dead Executor Probe]
                       SpoolRecoveryJob (30s)      JobReconciliationSweep
                                 |                             |
                                 +--------------+--------------+
                                                |
                                                v
                                 Claims atomic DB lease (30s)
                                 Downloads & verifies payload:
                                   * CWE-409 bounded decompression (5MB/15MB)
                                   * Security envelope verification
                                   * ContextPatchApplier path & revision validation
                                                |
                                                v
                                 Applies patch to context.json
                                 (Protected by ContextWriteLock)
                                                |
                                                v
                                 deliveryState = RECOVERED
                                 Deletes spooled patch
```

---

## Delivery state lifecycle

Each `Step` tracks its final structured output persistence status in `deliveryState`:

| `DeliveryState` | Description |
| :--- | :--- |
| `NOT_DURABLE` | Default initial state for running steps, or when running with unshared local storage. |
| `ACKED` | Synchronously delivered by the executor and acknowledged with HTTP 200 by `/context/v1/{jobId}`. |
| `SPOOLED` | API was unreachable; executor durably spooled the compressed JSON patch to cloud object storage. |
| `RECOVERED` | Clustered background sweeper (`SpoolRecoveryJob`) or dead-executor probe claimed the spool, applied the patch via `ContextPatchApplier`, and updated the step. |
| `EXPIRED` | Spool exceeded the retention window (default 24h) or was rejected due to decompression/envelope/path invalidity. The corrupt spool is deleted and further retries are suppressed. |

---

## Upgrade and deployment order

### Rule: upgrade the API before the executor
1. **API First:** Deploy `terrakube-api` with the Liquibase migrations (`changelog-2.34.0-step-delivery-state.xml`).
   * The API introduces the `delivery_state`, `delivery_revision`, `delivery_timestamp`, `lease_owner`, and `lease_until` columns.
   * `StepDeliveryStateHook` prevents external modification of `RECOVERED` / `EXPIRED`.
   * `SpoolRecoveryJob` starts sweeping pending spools.
2. **Executor Rollout:** Deploy new `terrakube-executor` images.
   * Executors publish synchronous final snapshots via `patchContext` using monotonic `ContextRevision.next(jobId)` and fall back to object storage spooling upon delivery failure.

### Backward and forward compatibility
* **New Executor against Older API:** If an executor image runs against an older API lacking `deliveryState` attributes, the step PATCH returns HTTP `400 Bad Request` or `404`. The executor intercepts `FeignException`, logs a warning, and immediately retries the PATCH using plain `StepAttributes` (without delivery fields). Job status and output logs are preserved.
* **Older Executor against Newer API:** Older executors do not supply `deliveryState`; the step defaults to `NOT_DURABLE` and functions normally.

---

## Failure scenarios and runbooks

### 1. Transient API outage during step completion
* The executor attempts synchronous delivery with exponential backoff (up to 4 attempts).
* If the API remains unreachable, the executor writes `context-spool/<org>/<job>/<step>-<phase>.json.gz` to cloud storage.
* If the API recovers before the executor terminates, the executor marks the step `SPOOLED`. The API's `SpoolRecoveryJob` claims the step via atomic DB lease and merges the patch into `context.json`.

### 2. Complete API outage leading to pod eviction or termination
* If the API remains down and the executor pod terminates before it can record `SPOOLED` on the step:
  * The step remains in `running` with `deliveryState = NOT_DURABLE`.
  * After the heartbeat key `executor-job-heartbeat:<jobId>` expires in Redis, `JobReconciliationSweep.failIfExecutorHeartbeatExpired` triggers a dead-executor storage probe.
  * The probe inspects the storage spool folder, recovers the spooled patch into `context.json`, marks the step `RECOVERED`, and marks the dead job as `failed`. This only recovers what the executor had already spooled. If it died before writing the spool, that final snapshot is not recoverable and the step stays `NOT_DURABLE`; see the [output delivery contract](output-delivery-contract.md).

### 3. Invalid or corrupted spool payloads
* If a spooled file is unparseable, exceeds decompression limits (5MB compressed / 15MB uncompressed), fails envelope matching, or contains invalid patch paths (e.g. invalid slash segments):
  * `SpoolRecoveryService` logs an error, deletes the invalid file from object storage, and transitions the step to `EXPIRED`.
  * The step is not retried on subsequent sweeps.
