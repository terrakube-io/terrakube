# ADR 0001: Pull-based persistent executor coordination

- Status: Proposed
- Date: 2026-08-31
- Decision owners: Terrakube maintainers
- Protocol contract: [Executor coordinator protocol v1](../executor-coordinator-protocol-v1.md)

## Context

Terrakube currently dispatches persistent execution work by resolving an agent URL and sending the
complete executor context to that URL. The API and executor authenticate this request with a shared
internal signing secret. This push model requires the API to reach every executor, exposes an
inbound executor endpoint, and couples remote executors to deployment-level credentials and
infrastructure.

The target experience is similar to a pull-based runner: an executor establishes an outbound HTTPS
connection to Terrakube, identifies its pool and instance, waits for compatible work, receives a
time-bounded lease, and reports progress and the terminal result through the coordinator API.

This ADR covers persistent executors. API-created ephemeral Kubernetes executors remain a separate
transport and are not deprecated by this decision.

## Decision

Terrakube will add a versioned pull coordinator alongside the existing persistent push transport.
The connection mode is selected per executor pool. Existing pools remain in `PUSH` mode until an
administrator explicitly migrates them; new releases must not silently convert stored pools or
running work.

The coordinator uses a durable database-backed task and lease state as its source of truth. Redis
may wake long-poll requests and reduce pickup latency, but losing Redis must not lose tasks, leases,
or terminal results.

The first protocol version is the integer `1`. Every coordinator request carries:

```http
X-Terrakube-Executor-Protocol: 1
```

The API publishes its supported version range and returns a structured `426 Upgrade Required` for
an unsupported version. Protocol validation happens before executor authentication so a compatible
client does not receive a misleading `401` or an unhandled `500` for a version mismatch.

## Domain terms

### Executor pool

An organization-owned logical execution target. A pool contains routing policy, tags, concurrency
limits, authentication configuration, and a connection mode. A workspace may select a pool. A pool
does not represent a process or a network address.

### Executor instance

One running executor manager process registered to a pool. An instance has a stable `systemId`, a
server-assigned `instanceId`, version and platform metadata, advertised capacity, and a last-contact
timestamp. Multiple instances may share one pool credential and compete for that pool's tasks.

### Task

One durable, schedulable unit of Terrakube execution for a specific job step. A task contains stable
references to its organization, workspace, job, step, and pool. Secret-bearing execution context is
materialized only after a lease is granted and is not stored in the task row as plaintext.

### Lease

A time-bounded, exclusive grant allowing one executor instance to work on one task. Every lease has
a unique `leaseId`, an expiry time, and a monotonically increasing fencing token. Only the current
lease may update task state or submit a terminal result.

## Task state model

The state model is:

```text
AVAILABLE ── claim ──▶ LEASED ── accept ──▶ RUNNING ── complete ──▶ COMPLETED
    ▲                    │                     ├── failure ────────▶ FAILED
    │                    │                     ├── cancel ack ─────▶ CANCELLED
    │                    │                     └── lease expires ──▶ ORPHANED
    │                    │                                               │
    │                    └── lease expires / decline                     │
    │                                                                    │
    └──────── explicit reconciliation retry when safe ───────────────────┘

AVAILABLE / unaccepted LEASED ── cancel ──▶ CANCELLED
```

`LEASED` expiry returns a task to `AVAILABLE` because execution was never acknowledged. A
`RUNNING` lease expiry does **not** automatically requeue the task. Terraform apply and destroy
operations can continue after a network partition; immediately issuing the same work to another
executor could duplicate non-idempotent side effects. Such work becomes `ORPHANED` and enters
reconciliation. Reconciliation may requeue a task only when the task is explicitly retry-safe or an
operator or workflow policy authorizes the retry. Otherwise it marks the task `FAILED` with a clear
executor-loss reason.

Fencing prevents a stale executor from changing Terrakube state after a replacement lease is
issued. It cannot stop an isolated process from continuing to interact with an external cloud, so
the protocol does not claim exactly-once infrastructure side effects.

Terminal states are `COMPLETED`, `FAILED`, and `CANCELLED`. Terminal state transitions are
immutable. Repeated delivery of the same terminal request returns the stored outcome; a conflicting
terminal request is rejected.

## Registration and authentication

Terrakube supports two executor authentication methods.

### Pool authentication token

1. An administrator creates a pull executor pool.
2. Terrakube displays a pool authentication token once.
3. One or more executor instances register with that token and a stable `systemId`.
4. Terrakube stores only a token hash and a non-secret prefix used for identification.
5. An administrator can rotate or revoke the token and pause the pool.

Reusing a pool token groups multiple instances under the same routing and authorization policy. A
revoked token cannot request new work. Rotation has an explicit overlap window so a rolling update
does not disconnect the entire pool.

### OIDC workload identity

An executor may instead present a short-lived token from a configured OIDC issuer. The token must
use a dedicated `terrakube-executor` audience, and its configured claim conditions map it to exactly
one executor pool. OIDC executor identity is distinct from user and team identity and grants no
general JSON:API permissions.

The executor must renew and reread workload tokens as required by the issuer. Terrakube does not
persist the external token.

### Job authorization

After granting a lease, Terrakube issues a short-lived job token scoped to the current pool,
instance, task, job, step, and lease. The token expires no later than the lease and only authorizes
the coordinator, log, result, state, and registry operations required by that task. Pull executors
must not receive the deployment-wide internal signing secret.

## Retry and idempotency decision

- Network failures, `429`, and retryable `5xx` responses use bounded exponential backoff with
  jitter and honor `Retry-After`.
- A long-poll `204 No Content` is a normal empty result, not a failure.
- Clients do not blindly retry authentication, authorization, validation, version, or lease
  conflict errors.
- Mutating requests carry an `Idempotency-Key`. The same principal, route, task, lease, key, and
  payload return the original response.
- Reusing an idempotency key with a different payload is rejected with `409 Conflict`.
- Log chunks carry a stable stream identifier and monotonically increasing sequence number;
  duplicate chunks are acknowledged but not appended twice.
- A lease and its fencing token are checked on every task mutation. A request from a superseded
  lease is rejected even when its idempotency key has not been seen before.
- Job command failure is terminal and is not treated as transport retry. Workflow-level retry is a
  separate, explicit action that creates a new task attempt.

Detailed endpoint behavior and error codes are defined by the versioned protocol contract.

## Compatibility requirements

- The new API must continue to dispatch to existing push executors throughout the Terrakube 2.x
  deprecation window.
- Existing agent rows migrate to `PUSH`; their URLs and workspace relationships remain unchanged.
- New executor binaries initially default to `PUSH` unless pull mode is explicitly configured.
- A newer API and an older push executor remain compatible.
- Within protocol v1, changes are additive: new response fields are optional and clients ignore
  unknown fields. Removing a field, making an optional field required, changing state semantics, or
  changing authentication behavior requires a new protocol version.
- The API advertises a range rather than one version so rolling upgrades can have an overlap period.
- Database changes are expand-only until the legacy runtime is removed in a major release.
- API-first rolling upgrade and executor-first rollback must be documented and tested.

## Rollout and deprecation boundary

The pull coordinator is introduced behind feature flags and does not change existing installations.
The planned release boundary is:

| Release | Pull mode                              | Push mode                                                                      |
| ------- | -------------------------------------- | ------------------------------------------------------------------------------ |
| `2.34`  | Technical preview, disabled by default | Default and fully supported                                                    |
| `2.35`  | Production-supported opt-in            | Default and fully supported                                                    |
| `2.36`  | Default for newly created pools        | Supported but deprecated; existing pools unchanged                             |
| `2.37`  | Default                                | Existing pools supported; new push pool creation requires a compatibility flag |
| `2.38`  | Default                                | Final 2.x runtime support; critical fixes only                                 |
| `3.0`   | Only pull persistent executor mode     | Push runtime removed after a pre-upgrade readiness check                       |

Changing this removal boundary requires an amendment to this ADR and must not happen as an
undocumented patch or minor-release change. No release may silently delete or convert legacy
configuration.

The deprecation applies to API-to-persistent-executor HTTP push, the executor's inbound job endpoint,
and the executor's dependency on the global internal secret. It does not deprecate ephemeral
Kubernetes execution or unrelated uses of Terrakube's internal authentication.

## Migration and rollback

A pool can switch modes only while drained: it has no leased or running tasks and its current push
endpoint is no longer accepting new work. A pull instance may register in standby before the switch
so the API can verify its identity, protocol version, and capacity without assigning work.

Mode activation is an atomic administrative operation. Rollback follows the same drain rule. A task
created for one transport is never offered through the other transport, including during rollback.

## Non-goals

- Replacing Terrakube jobs, steps, templates, or the Terraform/OpenTofu execution engine.
- Turning the Terrakube executor into a general-purpose CI runner.
- Replacing the API-created ephemeral Kubernetes executor model.
- Shipping a generic cloud VM or Kubernetes autoscaler in protocol v1.
- Removing an executor's need to reach its configured VCS, cloud APIs, or Terraform providers.
- Supporting interactive terminals or arbitrary bidirectional sessions in protocol v1.
- Guaranteeing exactly-once effects in external infrastructure after a network partition.
- Automatically converting existing push pools or changing workspace assignments during upgrade.

## Consequences

### Positive

- Remote executors require no inbound connectivity from Terrakube.
- Per-pool and per-job credentials replace the executor's deployment-wide secret.
- Durable leases make ownership, recovery, and fleet status explicit.
- Pool instances can scale horizontally and advertise capacity.
- Push and pull transports can coexist during a measured migration window.

### Costs and risks

- The API owns a new durable queue and long-poll workload.
- Lease and fencing correctness becomes security- and reliability-critical.
- Job payload creation must be separated from dispatch so short-lived credentials are generated at
  lease time rather than while waiting in the queue.
- Running executor loss requires reconciliation instead of automatic retry for unsafe operations.
- Supporting two persistent transports during deprecation increases the test and operational matrix.

## Alternatives considered

### Keep direct HTTP push

This preserves the smallest implementation but retains inbound networking, per-agent URLs, shared
credentials, and API-driven availability probing. It does not provide the desired runner-style
deployment model.

### Use Redis as the executor-facing queue

This removes inbound HTTP but requires every remote executor to reach Terrakube's Redis and receive
Redis credentials. It exposes an internal infrastructure dependency and makes secure multi-network
deployment harder. Redis remains suitable as a wake-up optimization, not as the public protocol or
source of truth.

### Maintain a WebSocket from every executor

A persistent bidirectional channel can reduce latency but adds connection affinity, proxy timeout,
backpressure, and reconnect complexity. Versioned HTTPS long polling provides the required outbound
connectivity with simpler failure semantics. A streaming transport may be added in a later protocol
without changing the task and lease model.
