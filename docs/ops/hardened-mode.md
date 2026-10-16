# Hardened mode

Hardened mode treats every run as untrusted code. Terraform, its providers, `local-exec`
provisioners and template hooks all execute inside the runner, so anything the runner holds is
reachable by the workspace author. In the default mode a runner holds the internal secret, the
storage credentials, Redis credentials and (on Kubernetes) its service account token: any workspace
can read or rewrite every other workspace's state.

In hardened mode the runner holds nothing but a token scoped to its own job step. It does not even
receive the repository credentials: the API clones the workspace source and hands the runner an
archive.

## What changes

| | Default | Hardened |
|---|---|---|
| Where a run executes | persistent executor, self-hosted agent, or opt-in ephemeral job | always a fresh ephemeral Kubernetes job per step |
| Runner credentials | internal secret, storage keys or cloud identity, Redis | an RS256 token for `organization/workspace/job/step`, nothing else |
| Workspace source | cloned by the runner with the VCS token or SSH key | cloned by the API, served to the runner as an archive; the same archive is reused by every step of the job |
| Terraform backend | `s3` / `azurerm` / `gcs` / `local` written by the runner, talking to storage directly | `http` backend on the API (`/tfstate/v1/runner/...`), storage written by the API; the job token reaches Terraform through `TF_HTTP_PASSWORD` and is never written to the configuration or to saved plans |
| Plans, outputs, state history, logs, heartbeat | written by the runner to storage and Redis | sent to the API with the job token |
| Workspace variables shaping the pod (`EPHEMERAL_*`, `PVC_CLAIM_NAME`, `TF_PLUGIN_CACHE_DIR`, `TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR`) | honoured | ignored |
| Workspace agent, `TERRAKUBE_DEFAULT_EXECUTOR` | honoured | ignored |
| Job payload | in the Kubernetes Job spec | in a per-job Secret owned by the Job |
| Remote state shared through "global remote state" | readable by every job of the organization | not readable by runners: a runner only reads workspaces that list it as a remote state consumer |

Persistent executors and self-hosted agents are not supported in hardened mode: a long-lived
process that runs one workspace after another can be left compromised by one job for the next.
Hardened mode refuses them instead of documenting the risk:

- the API never dispatches a hardened job anywhere but a runner pod it creates;
- an executor started with `TerrakubeHardenedMode=true` and without `EphemeralFlagBatch=true` refuses to start;
- an executor that is not in hardened mode refuses a job carrying a hardened token.

## What the job token can do

The token is minted by the API for one step and carries the step's flow type. The API only accepts it
on an explicit list of endpoints and checks, on every call, that the job and the step are still
running.

- read its workspace's current state; lock and unlock it while its step runs;
- write the state and its history only from a running apply, destroy or custom script step;
- upload the plan and output of its own step, download the plan of its own job;
- report its own job and step progress: status (never `approved`), whether the plan has changes, commit, its own step completion marker, output and plan locations
  pointing at the API. Every other job field (`autoApply`, `overrideBranch`, `approvalTeam`, ...) is refused;
- append logs and read/write the context of its own job;
- read the state of other workspaces of the same organization that list it as a remote state consumer;
- download modules of its own organization from the registry.

Everything else answers 403: other workspaces and organizations, history records, personal or team
token creation, the TFE run/project APIs, GraphQL, rollback and administration endpoints. A finished
job's token can no longer write anything or read state.

## Configuration

Hardened mode is set by the operator on each component. It cannot be enabled, disabled or weakened
from workspace, collection or global variables.

### API

| Variable | Required | Description |
|---|---|---|
| `TerrakubeHardenedMode` | yes | `true` |
| `TerrakubeRunnerTokenPrivateKeyPath` | yes | PKCS#8 PEM RSA key (2048 bits or more) signing job tokens. Must not be the dynamic credentials key. |
| `TerrakubeRunnerTokenPublicKeyPath` | yes | Matching X.509 PEM public key |
| `TerrakubeRunnerTokenTtlMinutes` | no | Token lifetime, default `360` |
| `TerrakubeRunnerServiceAccount` | yes | Service account of runner pods. It must carry no cloud identity (no IRSA, workload identity or similar annotation). |
| `TerrakubeRunnerConfigMap` | yes | ConfigMap with the runner's non-secret settings, see below |
| `ExecutorEphemeralNamespace`, `ExecutorEphemeralImage` | yes | As for ephemeral executors |
| `ExecutorEphemeralActiveDeadlineSeconds` | yes | Must not exceed the token lifetime, so a runner never outlives its token |

The API needs, on top of the ephemeral executor RBAC, `get/create/update/patch/delete` on `secrets`
in the runner namespace, and network access to the VCS providers (it clones the sources).

`ExecutorEphemeralSecret` is not mounted in hardened mode. The API refuses to start when any
required setting is missing or inconsistent.

### Runner ConfigMap

Only non-secret settings belong here, for example:

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: terrakube-runner-config
data:
  AzBuilderApiUrl: https://terrakube-api.example.com
  TerrakubeApiUrl: https://terrakube-api.example.com
  TerrakubeRegistryDomain: terrakube-registry.example.com
  TerrakubeEnableSecurity: "true"
  TerrakubeToolsRepository: https://github.com/AzBuilder/terrakube-extensions.git
  TerrakubeToolsBranch: main
```

The API sets `TerrakubeHardenedMode`, `TerraformStateType=ApiTerraformStateImpl` and `LogViaApi`
on the container itself, after the ConfigMap, so the ConfigMap cannot override them.

### Registry

| Variable | Description |
|---|---|
| `TerrakubeRunnerTokenPublicKeyPath` | Public key of the runner tokens. Without it the registry refuses runner tokens. |

### Infrastructure prerequisites

- The runner service account has no RBAC and no cloud identity; the API mounts no token in runner pods.
- Block the instance metadata endpoint for runner pods (for example IMDSv2 with a hop limit of 1, or a NetworkPolicy).
- Restrict the storage bucket to the API identity only. Runners do not need it, and a bucket policy
  that only trusts the API turns any misconfiguration into a failed run instead of an exposure.
- Runner pods should only reach the API, the registry, the VCS and the package mirrors they need.

## State locks

Terraform locks the state through the API. A lock held by a step that is no longer running is
considered stale and is taken over by the next run. An operator holding the manage-state permission
can also clear it explicitly:

```
DELETE /tfstate/v1/lock/organization/{organizationId}/workspace/{workspaceId}
```

## Runner self-check

Before running anything, a hardened runner checks its own environment and refuses to execute, failing
the job with the reason, when it finds:

- no job token, a token that is not hardened, or a token for another job;
- an internal secret, storage credentials or Redis settings;
- cloud credentials in the environment (`AWS_*`, `GOOGLE_APPLICATION_CREDENTIALS`, `AZURE_*`);
- a Kubernetes, EKS or Azure workload identity token file;
- a reachable instance metadata endpoint (EC2, ECS, EKS Pod Identity, IPv6 IMDS);
- any other state type than `ApiTerraformStateImpl`, or logs not sent through the API.

## Compatibility

- Default mode is unchanged. Nothing above applies unless `TerrakubeHardenedMode=true`.
- Existing state keeps its location: the API writes the same storage keys the runner backends used.
- Dynamic provider credentials keep working and no longer need access to the state bucket: the
  backend authenticates to the API with the job token, so `AWS_ROLE_ARN` and
  `AWS_WEB_IDENTITY_TOKEN_FILE` only affect providers.
- Workspaces relying on `EPHEMERAL_*` variables (node selectors, tolerations, resources, extra
  ConfigMaps or volumes) lose those customisations; set the equivalents on the API instead.
- Binary and OPA caches are not shared between runners in hardened mode.
- Private modules fetched over SSH with the workspace module key, and private extension repositories,
  are not available: the runner never receives those credentials. Publish modules to the registry instead.
- Policy evaluation runs inside the runner, so its results are as trustworthy as the code being
  evaluated. Policy-driven approval team routing is therefore not applied; approvals fall back to the
  workspace's regular approvers.
- Workspaces relying on "global remote state" must list their consumers explicitly.
- Terraform or OpenTofu must support the `http` backend with `lock_method`/`unlock_method` (all supported versions do).
