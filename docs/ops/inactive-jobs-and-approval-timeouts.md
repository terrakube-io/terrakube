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
- A workspace runs one job at a time. A `pending` or `queue` run behind an unfinished run of the same
  workspace has not started, so it is not closed while that run is unfinished (the run in front has its
  own limit), and the time it waited for its turn does not count: its clock starts when the run in front
  of it last changed.
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
- The steps are closed first, so the notification can describe the step that failed.
- The pass takes the same per-run lock (`job-execution-lock:<id>`) as the scheduler and the
  reconciliation sweep, and skips a run that another worker holds until the next pass. If Redis cannot be
  reached nothing is closed.
- `workspace.lastJobStatus` and `lastJobDate` are updated, the live status event is published, and
  notifications (Slack, Teams, webhook) and the VCS commit status (`failure` for both) are sent as for
  any other failure or cancellation.

## Workspace status self-repair

The reconciliation sweep (every 30 seconds) also corrects a workspace that shows an active status
(`pending`, `approved`, `waitingApproval`, `queue`, `running`) although none of its runs is active.
That is what any status change made without the API hooks would leave behind. It sets the status from
the workspace's newest run (or `NeverExecuted`, without a date), up to 100 workspaces per sweep, and
publishes the live status event so open pages update. The correction is one conditional update of the
status and the date: it does nothing if a run became active, or the status changed, since the workspace
was found, and it never writes the rest of the workspace. Each repair is counted in `terrakube_scheduler_workspace_status_repaired_total{stale}`. With
`io.terrakube.api.scheduler.reconciliation.auto-remediate=false` it only logs.
