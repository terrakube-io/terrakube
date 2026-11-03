# Module version refresh

The API reads the Git tags of every registry module and publishes each SemVer tag as a module version.
One Quartz job (`TerrakubeV2_ModuleRefreshPoller`, every minute, clustered) does this for all modules.
Releases before this one used one job per module; see [Upgrading](#upgrading).

## How a refresh runs

1. Each minute, one replica selects the modules that are due: `next_refresh` is empty (new or changed
   modules) or in the past. Empty ones go first. Modules of disabled organizations are skipped.
2. It claims each module by writing a lease expiry into `next_refresh`. Only one replica can claim a
   module, and it claims no more modules than its refresh pool has room for. As pool threads free up it
   claims more, for up to 50 seconds per run, while due modules remain.
3. A pool thread runs one `ls-remote` per module. No database transaction is open during the read.
4. The result is written only if the module still holds the same lease. A refresh that outlived its
   lease, or a module that was synced or changed meanwhile, writes nothing and is read again.

A replica that dies during a refresh only delays its modules until their leases run out.

A new version is in the API as soon as its refresh finishes, but the registry caches each module's
version list for `ModuleVersionsCacheTtlSeconds` (600 s by default). That cache still bounds how soon a
refreshed or synced version reaches `terraform init`.

## When a module is read next

After a successful read, the interval depends on when a new tag was last found
(`last_tag_detected_at`):

| Last new tag | Interval |
|---|---|
| Within `active-window-days` (30) | `active-interval-minutes` (15) |
| Within `idle-window-days` (90), or unknown | `idle-interval-minutes` (60) |
| Older | `dormant-interval-minutes` (360) |

After a failed read, the module is read again after `error-interval-minutes` (60). Every interval is
moved by up to `jitter` (15 %) either way, so modules do not refresh in lockstep.

A change to a module's source, VCS connection, SSH key or tag prefix makes it due at once.

## Failures

A failed read is stored on the module (`lastRefreshError`, up to 1024 characters) with its cause:
`auth`, `not_found`, `timeout`, `no_tags` (the repository has no tags) or `other`. The module page shows
it as a warning until a read succeeds.

## Checking for new versions now

The module page has a **Check for new versions** action for users who can manage modules. It calls:

```text
POST /module/v1/organization/{organizationId}/module/{moduleId}/sync
```

The API answers `202 Accepted` and marks the module due; the next poll (within a minute) reads it. It
requires the "manage modules" permission in the module's organization, and internal tokens are refused.

## Settings

All settings have the prefix `io.terrakube.api.module.refresh.`

| Setting | Environment variable | Default |
|---|---|---|
| `active-interval-minutes` | `ModuleRefreshActiveIntervalMinutes` | `15` |
| `idle-interval-minutes` | `ModuleRefreshIdleIntervalMinutes` | `60` |
| `dormant-interval-minutes` | `ModuleRefreshDormantIntervalMinutes` | `360` |
| `active-window-days` | `ModuleRefreshActiveWindowDays` | `30` |
| `idle-window-days` | `ModuleRefreshIdleWindowDays` | `90` |
| `error-interval-minutes` | `ModuleRefreshErrorIntervalMinutes` | `60` |
| `jitter` | `ModuleRefreshJitter` | `0.15` (at most `0.5`) |
| `lease-minutes` | `ModuleRefreshLeaseMinutes` | `5` |
| `executor.pool-size` | `ModuleRefreshExecutorPoolSize` | `4` |
| `executor.queue-capacity` | `ModuleRefreshExecutorQueueCapacity` | `16` |

The lease must cover every refresh a replica has queued, each of which can take up to a minute (two
30-second Git timeouts). At startup the API raises `lease-minutes` to
`ceil(queue-capacity / pool-size) + 1` if it is lower, and logs a warning.

Each run happens on one replica and uses that replica's pool, so throughput is set by `pool-size` and
the `ls-remote` time, not by the queue: about `pool-size × 60 / seconds per ls-remote` modules a minute.
With the default 4 threads and 1 s reads that is about 240 a minute, enough for roughly 3600 modules on
the 15-minute interval; with 5 s reads it is about 48 a minute, or about 700 such modules. Watch
`module.refresh.lsremote.duration`. If `module.refresh.overdue.oldest.age.seconds` keeps growing, add
threads; more replicas add little, since only one of them claims modules each minute.

## Metrics

| Metric | Meaning |
|---|---|
| `module.refresh.overdue.count` | Modules that are due and not claimed yet. |
| `module.refresh.overdue.oldest.age.seconds` | How long the longest-waiting module has been due. New and changed modules count as 0. |
| `module.refresh.result{outcome,cause}` | Refreshes by `success`/`failure` and cause (`none` on success). |
| `module.refresh.lsremote.duration` | Time spent reading the tags of one repository. |

## Upgrading

- **The fixed interval is gone.** `io.terrakube.api.module.refresh.interval` (`ModuleRefreshInterval`)
  is replaced by the activity-based intervals above. If it is still set, the API logs a deprecation
  warning at startup and keeps using it as the interval after a successful read (failures still use
  `error-interval-minutes`). Remove it to use the new intervals.
- **Existing modules start on the active interval.** The `2.35.0` database migration sets
  `last_tag_detected_at` to the upgrade time for every existing module, so they are read every 15
  minutes for the first 30 days, then fall back to 60 and 360 minutes unless a new tag shows up.
  Their `next_refresh` is empty, so they are all due right after the upgrade; the first polls work
  through them as fast as the pools allow.
- **Old per-module jobs are removed.** At startup the API deletes the stored `TerrakubeV2_ModuleRefresh_*`
  jobs of the old refresh job class. Module storage cleanup jobs now use the prefix
  `TerrakubeV2_ModuleDeleteStorage_`.
- **Rolling deploys.** While old and new replicas run side by side, an old replica that picks up the
  poller trigger cannot load the poller class, and Quartz can put the trigger in an error state, in
  which it stops firing. Every new replica recreates the poller job when it starts, and checks the
  trigger once a minute and resets it from the error state. Polling can pause while old replicas are
  still running and resumes within about a minute of the reset. Old replicas keep refreshing through their per-module jobs until the new ones
  delete those jobs; a per-module job that fires on a new replica deletes itself.
