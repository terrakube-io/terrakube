# Executor coordinator protocol v1

- Status: Draft
- Version: `1`
- Architecture decision: [ADR 0001](decisions/0001-pull-executor-coordination.md)

This document defines the compatibility and behavioral contract between the Terrakube API and
pull-based persistent executors. Payload schemas may gain optional fields while this document is in
Draft, but the state, ownership, authentication, versioning, and idempotency rules below are the v1
contract.

The terms **MUST**, **MUST NOT**, **SHOULD**, **SHOULD NOT**, and **MAY** describe protocol
requirements.

## Transport

- Production traffic MUST use HTTPS.
- JSON request and response bodies use `application/json` and UTF-8.
- Timestamps use UTC RFC 3339 format.
- Identifiers are opaque strings. Clients MUST NOT infer ordering or tenancy from an identifier.
- Executor and job bearer tokens MUST NOT appear in URLs, logs, metrics labels, or error details.

The v1 base path is:

```text
/api/v1/executor
```

## Version discovery and negotiation

An executor discovers the API range before registration or polling:

```http
GET /api/v1/executor/protocol
```

Successful response:

```http
HTTP/1.1 200 OK
X-Terrakube-Executor-Protocol-Min: 1
X-Terrakube-Executor-Protocol-Max: 1
Content-Type: application/json

{
  "currentVersion": 1,
  "minVersion": 1,
  "maxVersion": 1
}
```

This discovery endpoint MAY be unauthenticated because it exposes no tenant or executor data. All
other coordinator requests MUST include the selected integer version:

```http
X-Terrakube-Executor-Protocol: 1
```

The client selects the highest version supported by both ranges. Every coordinator response SHOULD
include the server's minimum and maximum headers, including error responses.

A missing or malformed header returns `400 Bad Request`:

```json
{
  "type": "urn:terrakube:executor:error:protocol-version-required",
  "title": "Executor protocol version is required",
  "status": 400,
  "code": "EXECUTOR_PROTOCOL_VERSION_REQUIRED",
  "minVersion": 1,
  "maxVersion": 1
}
```

An unsupported version returns `426 Upgrade Required` before authentication:

```http
HTTP/1.1 426 Upgrade Required
Upgrade: Terrakube-Executor/1
X-Terrakube-Executor-Protocol-Min: 1
X-Terrakube-Executor-Protocol-Max: 1
Content-Type: application/problem+json
```

```json
{
  "type": "urn:terrakube:executor:error:protocol-version-unsupported",
  "title": "Executor protocol version is not supported",
  "status": 426,
  "code": "EXECUTOR_PROTOCOL_VERSION_UNSUPPORTED",
  "requestedVersion": 2,
  "minVersion": 1,
  "maxVersion": 1
}
```

Protocol mismatch MUST NOT be reported as `401`, `403`, or `500`.

## Authentication layers

The protocol separates three authorization contexts.

### Pool authentication

A pool authentication token uses the `tkrt-` prefix and is shown only when created or rotated. The
API stores only a password-strength hash and a short non-secret token prefix. It authorizes
registration and polling for one pool, not access to general Terrakube APIs.

```http
Authorization: Bearer tkrt-REDACTED
```

Multiple instances MAY use one pool token. Each instance MUST provide a stable, locally persisted
`systemId` so the API can distinguish managers sharing the token.

### OIDC executor authentication

An executor MAY authenticate with a short-lived external OIDC token instead. The token MUST:

- have a configured issuer and valid signature and lifetime;
- contain the `terrakube-executor` audience;
- satisfy every configured claim condition;
- resolve to exactly one executor pool.

The resulting principal is an executor principal. It MUST NOT inherit a user's team membership or
general JSON:API access. The executor is responsible for renewing the token and rereading a
projected token file before expiry.

### Job authentication

The successful task request response contains a short-lived job token. Its effective authorization
is bound to the current `poolId`, `instanceId`, `taskId`, `leaseId`, fencing token, job, and step.
The API MUST reject the job token after lease expiry, lease replacement, explicit active-lease
revocation, or terminal task completion.

Pausing a pool or revoking its pool credential stops registration and new task requests but does not
silently invalidate already running leases. An administrator MAY explicitly cancel or emergency-
revoke those leases when immediate termination is required.

## Instance registration

```http
POST /api/v1/executor/instances/register
X-Terrakube-Executor-Protocol: 1
Authorization: Bearer <pool-token-or-oidc-token>
Idempotency-Key: <uuid>
```

Example request:

```json
{
  "systemId": "s_f70c33a2",
  "name": "prod-eu-west-1-a",
  "executorVersion": "2.34.0",
  "protocolMinVersion": 1,
  "protocolMaxVersion": 1,
  "platform": "linux",
  "architecture": "amd64",
  "capacity": 2,
  "tags": ["production", "eu-west-1"]
}
```

Example response:

```http
HTTP/1.1 200 OK

{
  "instanceId": "instance-01J7Z8Y5N6",
  "poolId": "pool-01J7Z8P21A",
  "systemId": "s_f70c33a2",
  "selectedProtocolVersion": 1,
  "status": "STANDBY",
  "heartbeatIntervalSeconds": 15,
  "leaseDurationSeconds": 60
}
```

Registration is idempotent for the same pool and `systemId`. It updates non-security metadata and
returns the existing `instanceId`. `systemId` uniqueness is scoped to a pool: one host may run
instances for multiple pools, and each `(poolId, systemId)` pair receives its own `instanceId`.

Executor-supplied tags, platform data, and capacity are inventory claims, not authorization. The
API intersects them with the pool's administrator-controlled routing policy and configured capacity
limit. Registration metadata cannot expand a pool's organization or workspace access.

An administrator activates the pool separately. Registration alone MUST NOT cause jobs to move from
the push transport to the pull transport.

## Requesting a task

```http
POST /api/v1/executor/tasks/request
X-Terrakube-Executor-Protocol: 1
Authorization: Bearer <pool-token-or-oidc-token>
Idempotency-Key: <uuid>
```

Example request:

```json
{
  "instanceId": "instance-01J7Z8Y5N6",
  "availableCapacity": 1,
  "waitSeconds": 50
}
```

The API holds the request until a compatible task is available or `waitSeconds` elapses. A lease is
created atomically before returning work. No available task returns `204 No Content`; this is a
normal result and does not count as an executor failure.

If the response containing a lease is lost, the executor retries with the same idempotency key and
the API returns the same lease rather than claiming a second task. The executor generates a new key
only after it has received and durably recorded the previous response or received `204`.

Example leased task response:

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "taskId": "task-01J7Z91HB3",
  "leaseId": "lease-01J7Z91K1Y",
  "fencingToken": 4,
  "leasedAt": "2026-08-31T14:30:00Z",
  "expiresAt": "2026-08-31T14:31:00Z",
  "jobToken": "REDACTED",
  "task": {
    "organizationId": "...",
    "workspaceId": "...",
    "jobId": "1234",
    "stepId": "...",
    "type": "terraformPlan",
    "payload": {}
  }
}
```

The API MUST NOT lease work from another pool, exceed advertised available capacity, or return a
plaintext task payload from a durable queue row. Secret-bearing payload fields are materialized only
for the granted lease.

The effective available capacity is the minimum of the request's `availableCapacity` and the
instance's registered capacity minus its active leases. Request fields cannot override pool routing
tags or authorization policy.

## Accepting and renewing a lease

The executor acknowledges that execution has started:

```http
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/accept
Authorization: Bearer <job-token>
Idempotency-Key: <uuid>
```

`accept` transitions `LEASED` to `RUNNING`. Repeating the same accepted request returns the current
lease representation. Acceptance after lease expiry returns `409 LEASE_EXPIRED`.

Before acceptance, an executor that discovers a local incompatibility MAY decline the lease:

```http
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/decline
Authorization: Bearer <job-token>
Idempotency-Key: <uuid>
```

A decline includes a stable reason code and a redacted diagnostic. It returns `LEASED` work to
`AVAILABLE`, increments its attempt count, and cannot be used after the task enters `RUNNING`.
Repeated incompatibility with the same pool is surfaced to an administrator instead of creating an
unbounded hot retry loop.

While running, the executor renews ownership:

```http
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/heartbeat
Authorization: Bearer <job-token>
Idempotency-Key: <uuid>
```

Example response:

```json
{
  "taskId": "task-01J7Z91HB3",
  "leaseId": "lease-01J7Z91K1Y",
  "fencingToken": 4,
  "state": "RUNNING",
  "expiresAt": "2026-08-31T14:31:15Z",
  "jobToken": "REDACTED",
  "cancelRequested": false
}
```

The executor SHOULD heartbeat at the server-provided interval and MUST NOT assume its local clock is
authoritative. The server response controls expiry. Heartbeat from a stale fencing token returns
`409 LEASE_SUPERSEDED`.

Each successful heartbeat rotates or refreshes the job token so its expiry remains no later than the
renewed lease expiry. The executor MUST atomically replace the active in-memory token before the
next callback and MUST NOT log either value.

## Lease loss and recovery

- A `LEASED` task whose lease expires before acceptance returns to `AVAILABLE` and increments its
  attempt count.
- A `RUNNING` task whose lease expires becomes `ORPHANED`.
- `ORPHANED` work is not automatically issued to another executor unless it is marked retry-safe.
- Reconciliation may mark unsafe orphaned work `FAILED` or wait for an administrator or workflow
  retry decision.
- When reconciliation creates a new attempt, it receives a new `leaseId`, job token, and higher
  fencing token.
- All callbacks from the old lease are rejected after fencing advances.

These rules prevent Terrakube from accepting two terminal results. They do not guarantee that an
isolated executor stopped interacting with external infrastructure.

## Logs

```http
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/logs
Authorization: Bearer <job-token>
Idempotency-Key: <uuid>
```

Example request:

```json
{
  "streamId": "stdout",
  "firstSequence": 120,
  "entries": [
    {
      "sequence": 120,
      "timestamp": "2026-08-31T14:30:10Z",
      "text": "Planning..."
    },
    {
      "sequence": 121,
      "timestamp": "2026-08-31T14:30:11Z",
      "text": "Refreshing state..."
    }
  ]
}
```

The tuple `taskId`, `leaseId`, `streamId`, and `sequence` uniquely identifies a log entry. Duplicate
entries are acknowledged and ignored. Conflicting content for an existing sequence returns
`409 LOG_SEQUENCE_CONFLICT`. The API MAY return `429` with `Retry-After` to apply backpressure.

Executors MUST redact configured secrets before submission. The API applies its own redaction and
size limits and persists or publishes logs through server-side infrastructure; a pull executor does
not connect directly to Terrakube Redis.

## Cancellation

Cancellation is cooperative. The API sets `cancelRequested=true` in heartbeat responses. The
executor SHOULD stop the child process, persist any permitted final logs, and submit a cancelled
terminal result.

A task cancelled while `AVAILABLE` or unaccepted `LEASED` moves directly to `CANCELLED`. A running
task is not reported as cancelled until the executor acknowledges cancellation or reconciliation
determines that it is no longer running.

## Terminal results

Successful completion:

```http
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/complete
Authorization: Bearer <job-token>
Idempotency-Key: <uuid>
```

Execution failure or acknowledged cancellation uses the corresponding endpoint:

```text
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/fail
POST /api/v1/executor/tasks/{taskId}/leases/{leaseId}/cancelled
```

The terminal payload includes an outcome, exit code, final log sequence, output references, and
diagnostic summary. Large state, plan, and output objects are uploaded through dedicated scoped
endpoints or server-issued upload URLs; they are not embedded in an unbounded terminal request.

The first valid terminal transition wins. Retrying the identical terminal request returns `200`
with the stored terminal representation. A different terminal outcome returns
`409 TASK_ALREADY_TERMINAL`. A stale or expired lease returns a lease-specific `409` and MUST NOT
modify the job or step.

## Idempotency

Every mutating endpoint requires:

```http
Idempotency-Key: <client-generated-uuid>
```

The API scopes a key to the authenticated principal and route, plus task and lease when they already
exist. It stores the request digest and outcome for at least the lifetime of the task and its
terminal-result retention window.

- Same key and same request digest: return the original status and body.
- Same key and different request digest: `409 IDEMPOTENCY_KEY_REUSED`.
- New key against a stale fencing token: `409 LEASE_SUPERSEDED`.
- New key repeating the same terminal outcome: return the stored terminal representation.

Clients MUST reuse the same key when the request may have reached the server but the response was
lost. They MUST generate a new key for a semantically new heartbeat, log batch, or state change.

## Retry behavior

| Response                        | Executor behavior                                                         |
| ------------------------------- | ------------------------------------------------------------------------- |
| Long-poll `204`                 | Request again; this is not an error                                       |
| `408`, `429`                    | Retry with bounded exponential backoff and jitter; honor `Retry-After`    |
| Retryable `5xx`                 | Retry with bounded exponential backoff and jitter                         |
| Network timeout before response | Retry with the same idempotency key for mutations                         |
| `401`                           | Refresh an OIDC token once when applicable; otherwise stop accepting work |
| `403`                           | Do not retry; configuration or pool authorization must change             |
| `409`                           | Interpret the structured lease/idempotency code; do not retry blindly     |
| `426`                           | Stop polling and report the supported API range                           |

Backoff MUST have an upper bound. An executor SHOULD remain observable as degraded while retrying
and MUST NOT spin in a tight loop when the API is unavailable.

## Error format

Coordinator errors use `application/problem+json` and include a stable machine-readable `code`:

```json
{
  "type": "urn:terrakube:executor:error:lease-superseded",
  "title": "Executor task lease has been superseded",
  "status": 409,
  "code": "LEASE_SUPERSEDED",
  "taskId": "task-01J7Z91HB3",
  "leaseId": "lease-01J7Z91K1Y",
  "traceId": "01J7Z9Q7H5"
}
```

Errors MUST NOT include bearer tokens, task secrets, environment variables, VCS credentials, SSH
keys, or raw job payloads.

The initial stable error codes are:

```text
EXECUTOR_PROTOCOL_VERSION_REQUIRED
EXECUTOR_PROTOCOL_VERSION_UNSUPPORTED
EXECUTOR_AUTHENTICATION_FAILED
EXECUTOR_POOL_FORBIDDEN
EXECUTOR_POOL_PAUSED
INSTANCE_POOL_MISMATCH
INSTANCE_PROTOCOL_INCOMPATIBLE
TASK_NOT_FOUND
LEASE_EXPIRED
LEASE_SUPERSEDED
LEASE_NOT_ACCEPTED
TASK_ALREADY_TERMINAL
IDEMPOTENCY_KEY_REUSED
LOG_SEQUENCE_CONFLICT
```

## Compatibility rules

Protocol v1 allows:

- adding optional request or response fields;
- adding new error codes while preserving HTTP semantics;
- adding endpoints that do not change existing endpoint behavior;
- increasing server limits without changing payload meaning.

Protocol v1 does not allow:

- removing or renaming a field;
- making an optional field required;
- changing the meaning of an existing state or error code;
- weakening pool, job-token, lease, or fencing validation;
- changing idempotency scope or terminal-result precedence;
- automatically retrying `RUNNING` work that v1 classifies as unsafe.

Any incompatible change requires protocol v2 and an API overlap window in which at least one
released executor version shares a supported protocol with both the old and new API versions.

Executors MUST ignore unknown response fields. The API SHOULD ignore unknown request fields unless
they create ambiguity or a security risk. Clients MUST NOT depend on JSON field order or undocumented
error text.

## Protocol v1 non-goals

- A generic CI job format.
- Interactive shells or session forwarding.
- A cloud-provider autoscaling API.
- Direct executor access to Terrakube Redis.
- Migration of ephemeral Kubernetes execution.
- Automatic conversion of push pools.
- Exactly-once external infrastructure side effects.
