# Runbook: zero-pending job reconciliation & executor queue liveness

Covers jobs that get stuck non-terminal (`pending` / `approved` / `waitingApproval` / `queue` /
`running`) with **no remaining executable step**, which used to block the shared executor FIFO
queue indefinitely. Design: `docs/superpowers/specs/2026-09-02-zero-pending-job-reconciliation-design.md`.

## Symptom

- Workspace runs sit `pending` while executor pods are Ready and idle.
- API logs repeat: `Job <id> Step <sid> is not yet the oldest job waiting for the executor pool, will retry`.
- The real queue head is a much older job in `approved` (or `pending`) with all steps `completed`.

## Diagnose

Call the admin report (needs an instance-owner group token or an internal service token):

```
GET /admin/v1/scheduler/reconciliation
```

Each entry: `jobId`, `currentStatus`, `derivedOutcome`, `targetStatus`, `disposition`
(`DRY_RUN` = a safe deterministic transition is available, `HELD_ANOMALY` = needs a human),
and `evidence` (every step's number + status).

Metrics to check (Prometheus names):

| Metric | Meaning |
|---|---|
| `terrakube_scheduler_zero_pending_nonterminal_total{status}` | stuck jobs observed, by current status |
| `terrakube_scheduler_zero_pending_reconciliations_total{outcome}` | transitions applied, by target status |
| `terrakube_scheduler_reconciliation_anomalies_total` | jobs the routine could not safely resolve |
| `terrakube_scheduler_executor_queue_depth` | jobs currently eligible for the pool |
| `terrakube_scheduler_executor_queue_head_age_seconds` | age of the eligible FIFO head |
| `terrakube_scheduler_executor_queue_head_job` | numeric id of the eligible FIFO head (`-1` = empty) |
| `terrakube_scheduler_quartz_trigger_recreated_total` | sweep re-created a missing trigger |
| `terrakube_scheduler_quartz_trigger_races_total` | concurrent trigger create/delete contention |
| `quartz_jobs_executing` | Quartz jobs running on this instance right now |

## Recover

Automatic: with `auto-remediate=true` (default) the 30s sweep resolves deterministic
`completed` / `noChanges` / `failed` / `cancelled` / `rejected` cases on its own. Nothing to do.

Manual (dry-run reporting only, or `auto-remediate=false`):

```
POST /admin/v1/scheduler/reconciliation
{ "confirm": true, "jobIds": [755, 756, 759] }
```

- Only entries whose `derivedOutcome` is a terminal transition are applied; `HELD_ANOMALY`
  entries are returned untouched.
- Omit `jobIds` to apply every deterministic entry.
- `"confirm": false` (or missing) → `400`, nothing changes.
- Every applied transition is logged: `Admin reconciliation applied: job <id> <from> -> <to> (<disposition>)`.

Anomalies (`HELD_ANOMALY`): inspect `evidence`. A job with an unexpected step-status mix or no
steps at all is kept for investigation and excluded from executor dispatch - it will not block
later jobs. Resolve it by hand (correct the step rows, or cancel the job through the normal API).

## Alerts (PromQL sketches - not shipped, add to your stack)

```
# A stuck job has been visible longer than the grace period.
increase(terrakube_scheduler_zero_pending_nonterminal_total[15m]) > 0
  and max_over_time(terrakube_scheduler_executor_queue_head_age_seconds[15m])
      > <anomaly-grace-seconds>

# Idle executors, blocked queue.
terrakube_scheduler_executor_queue_head_age_seconds > 300
  and max_over_time(quartz_jobs_executing[10m]) == 0

# The routine keeps hitting cases it cannot resolve.
increase(terrakube_scheduler_reconciliation_anomalies_total[1h]) > 0
```

## Configuration (`io.terrakube.api.scheduler.reconciliation.*`)

| Property | Env | Default | Effect |
|---|---|---|---|
| `sweep-enabled` | `SchedulerReconciliationSweepEnabled` | `true` | 30s sweep runs the routine; admin `POST` may apply. Off = trigger/heartbeat repair only. |
| `auto-remediate` | `SchedulerReconciliationAutoRemediate` | `true` | Sweep applies deterministic transitions. Off = dry-run (metrics + logs only). |
| `admission-guard-enabled` | `SchedulerReconciliationAdmissionGuardEnabled` | `true` | Guarded FIFO-admission queries (a job with steps but none pending stops being a blocker). |
| `anomaly-grace-seconds` | `SchedulerReconciliationAnomalyGraceSeconds` | `300` | Stale threshold for the report / alerts. |

The scheduler inline path (a live run that finds no next step reconciles itself) is always on.

### Cautious phased rollout

1. `auto-remediate=false`, `admission-guard-enabled=false` — observe the report + metrics.
2. `admission-guard-enabled=true` — idle executors stop being blocked by existing zombies.
3. `auto-remediate=true` — zombies clear automatically.
4. Validate a burst run across >=2 API replicas and multiple executor pods.
