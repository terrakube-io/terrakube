# Runbook: inactive run limits and approval timeouts

A Quartz job (`InactiveJobs`, every 5 minutes) and the scheduler itself close runs that stay open for
too long. Runs waiting for a person to approve are treated differently from runs that are stuck.

## Limits

| Setting | Environment variable | Default | Applies to |
|---|---|---|---|
| `io.terrakube.scheduler.inactive-jobs.max-age-hours` | `InactiveJobsMaxAgeHours` | `6` | `pending`, `approved`, `queue` and `running` runs. They are closed as `failed`. |
| `io.terrakube.scheduler.inactive-jobs.waiting-approval-timeout-hours` | `InactiveJobsWaitingApprovalTimeoutHours` | `0` (off) | `waitingApproval` runs. They are closed as `cancelled`. |

- The max age is counted from the run's creation, or from its approval when a person approved it, so
  time spent waiting for approval never counts against a run that was then approved.
- A run waiting for approval is **not** closed by default: approval is a human decision and can take
  days. Set the approval timeout only if your organisation wants a limit. It is counted from when
  the run started waiting (its last status change), not from its creation.
- Finished runs (`completed`, `failed`, `cancelled`, `rejected`, `noChanges`, `notExecuted`) are never
  closed by these limits.

## What happens to a closed run

- The run's status becomes `failed` (stuck run) or `cancelled` (approval timeout), and its pending
  or running steps get the same status.
- The reason is recorded on the run ("Closed by Terrakube: ...") and shown at the top of the run
  details.
- `workspace.lastJobStatus` and `lastJobDate` are updated, the live status event is published, and
  notifications (Slack, Teams, webhook) and the VCS commit status are sent as for any other failure
  or cancellation.

## Workspace status self-repair

The reconciliation sweep (every 30 seconds) also corrects a workspace that shows an active status
(`pending`, `approved`, `waitingApproval`, `queue`, `running`) although none of its runs is active.
That is what any status change made without the API hooks would leave behind. It sets the status from
the workspace's newest run (or `NeverExecuted`), up to 100 workspaces per sweep, and counts each
repair in `terrakube_scheduler_workspace_status_repaired_total{stale}`. With
`io.terrakube.api.scheduler.reconciliation.auto-remediate=false` it only logs.
