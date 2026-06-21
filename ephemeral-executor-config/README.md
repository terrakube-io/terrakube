# Ephemeral Executor — configuration, limitations & operational notes

In **ephemeral execution** mode the API creates a one‑shot Kubernetes `Job` per run
(instead of routing the run over HTTP to a long‑running executor Deployment). Each
Job clones the workspace, runs Terraform/OpenTofu, streams logs/output, and is then
disposed of. This enables parallelism across workspaces and per‑run isolation.

The manifests in this directory grant the API the RBAC it needs to manage those Jobs:

| file | purpose |
|------|---------|
| `service_account.yml`     | ServiceAccount the API runs as (referenced by `api.serviceAccountName`) |
| `rbac_role.yml`           | Role allowing `get/list/watch/create/update/patch/delete` on `batch/jobs` |
| `rbac_role_binding.yml`   | Binds the Role to the ServiceAccount |

> The Helm chart renders equivalent RBAC automatically when
> `api.ephemeralExecution.enabled=true`. These files are for non‑Helm installs.

## Enabling ephemeral execution

It requires **both**:

1. **Cluster/API side** — `api.ephemeralExecution.enabled=true` (Helm), which sets the
   API env (`ExecutorEphemeralNamespace`, `ExecutorEphemeralImage`,
   `ExecutorEphemeralSecret`, …) and the RBAC above.
2. **Per run** — the variable `TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR=1` must be present in
   the run's effective environment. Set it as an **organization global** ENV variable to
   make every workspace ephemeral, or per **workspace** to opt in selectively. A
   workspace‑level value **overrides** the organization global — set `=0` on a workspace
   to force it back onto the persistent executor (see limitations below).

Useful per‑run ENV variables consumed by the API when building the Job:

| variable | effect |
|----------|--------|
| `TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR` | `1` = run as ephemeral Job; `0` = use persistent executor |
| `EPHEMERAL_CPU_REQUEST` / `EPHEMERAL_MEMORY_REQUEST` | Job resource requests (default to the platform's defaults if unset) |
| `EPHEMERAL_CPU_LIMIT` / `EPHEMERAL_MEMORY_LIMIT` | Job resource limits |
| `EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP` | attach a ConfigMap to the Job as `envFrom` |
| `TF_PLUGIN_CACHE_DIR` + `PVC_CLAIM_NAME` | mount a pre‑existing PVC as the Terraform plugin cache (see "Disk" below) |
| `EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS`, `EPHEMERAL_CONFIG_TOLERATIONS`, `EPHEMERAL_CONFIG_ANNOTATIONS`, `EPHEMERAL_CONFIG_SERVICE_ACCOUNT` | scheduling / pod metadata overrides |

---

## Limitations & how to work around them

### 1. Job payload size — large‑variable workspaces fail to start

**Symptom:** the Job pod fails immediately (exit `255`) before any application log, with:

```
exec /cnb/process/web: argument list too long
```

**Cause:** the API serializes the **entire job payload** (all workspace + global
variables, base64‑encoded) into a **single environment variable** on the Job
(`EphemeralJobData`). Linux caps the length of *one* `argv`/`envp` string at
`MAX_ARG_STRLEN = 32 × PAGE_SIZE = 128 KiB`, enforced by `execve()` in the kernel —
**before** the container image runs. It is not a Kubernetes, cgroup, ulimit, or image
setting and cannot be raised. (The total `ARG_MAX` ~2 MB limit is separate and is not
what's hit here — it's the single‑string cap.)

Because the payload is base64, the practical ceiling is **~96 KiB of raw variable data**
per workspace (×4/3 ≈ 128 KiB). Workspaces above that cannot run ephemeral.

The **persistent executor does not have this limit** — the API delivers its payload in an
HTTP request body, which is effectively unbounded.

**Workarounds:**
- **Fall back to the persistent executor** for the affected workspace(s): keep at least
  one executor replica running and set `TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR=0` as a
  workspace variable. This is the simplest fix and is what large workspaces should use today.
- **Shrink the payload** below ~96 KiB raw — usually by moving large secret blobs out of
  Terraform variables into a secrets store (e.g. AWS Secrets Manager / SSM) referenced by
  ARN, rather than embedding their values inline.

To find affected workspaces, sum the size of each workspace's variable
keys+values; anything approaching ~96 KiB raw is at risk (add margin for the JSON
envelope the payload is wrapped in).

> Potential upstream fix: deliver `EphemeralJobData` to the Job via a mounted file /
> ConfigMap / volume instead of an environment variable, which would remove this limit
> entirely.

### 2. Ephemeral local storage — `terraform init` evicts the pod

**Symptom:** the run starts, downloads providers, then the pod is **Evicted** (exit `137`):

```
Pod ephemeral local storage usage exceeds the total limit of containers 1Gi
```

**Cause:** on managed platforms (notably **GKE Autopilot**) a pod with no explicit
`ephemeral-storage` request gets a small default (1 GiB). A real `terraform init` — large
providers (e.g. `hashicorp/aws` is several hundred MB) plus a deep module tree — easily
exceeds that, and the kubelet evicts the pod. Terrakube exposes CPU/memory knobs for the
Job but **no `ephemeral-storage` knob**, so you cannot simply raise the limit.

**Workaround — shared Terraform plugin cache on a PVC:** point `TF_PLUGIN_CACHE_DIR` at a
mount backed by a pre‑existing PVC named by `PVC_CLAIM_NAME` (default
`terrakube-plugin-pvc`). Providers are then written to the PVC instead of the pod's local
disk, which both **stops the eviction** and **speeds up subsequent runs** (providers are
reused instead of re‑downloaded). The PVC must already exist in the executor namespace; if
it's missing the API logs a warning and skips the mount.

```
# organization/workspace ENV variables
TF_PLUGIN_CACHE_DIR = /home/cnb/.terraform.d/plugin-cache
PVC_CLAIM_NAME      = terrakube-plugin-pvc
```

**GCP / GKE specifics for the PVC** (learned the hard way):
- For **parallel** ephemeral Jobs (different nodes) the PVC must be **`ReadWriteMany`**.
  On GKE that means **Filestore** (`filestore.csi.storage.gke.io`). Use the
  **`enterprise-multishare`** class so you can provision small (sub‑TiB) shares — the
  Basic/standard RWX classes have a 1 TiB minimum.
- The built‑in `*-rwx` StorageClasses provision the instance on the **`default`** VPC. If
  your cluster runs on a custom VPC, the nodes can't reach it and mounts time out
  (`DeadlineExceeded`). Use a **custom StorageClass** pinned to the cluster's network with
  `connect-mode: PRIVATE_SERVICE_ACCESS` and a `reserved-ip-range`.
- Filestore **Enterprise needs its own `/24`** in the private‑services‑access peering. If
  it shares an existing range (e.g. with Cloud SQL) you'll get
  `RANGES_EXHAUSTED — reserved IP range does not have enough space`. Allocate a dedicated
  `google_compute_global_address` (purpose `VPC_PEERING`) and add it to the
  `google_service_networking_connection`.
- Enable the **Cloud Filestore API** (`file.googleapis.com`) in the project.
- The StorageClass binds `WaitForFirstConsumer`, so the volume isn't provisioned until the
  first pod mounts it — the first run pays a one‑time provisioning delay (several minutes
  for an Enterprise instance); later runs are fast. The mount inherits the Job's
  `fsGroup`, so the executor user can write to the cache.

> Potential upstream fix: expose `EPHEMERAL_EPHEMERAL_STORAGE_REQUEST/LIMIT` knobs (mirroring
> the CPU/memory ones) so installs not using a shared cache can simply size the Job's disk.

---

## Deployment gotcha: a stale Redis secret overrides a new Valkey backend

Not strictly ephemeral, but it surfaced while bringing ephemeral up and bites any upgrade
that switches the cache from Redis to **Valkey**.

**Symptom:** after the upgrade the API (and ephemeral executor) can't reach the cache:

```
redis.clients.jedis.exceptions.JedisConnectionException: Failed to create socket
Caused by: java.net.UnknownHostException: terrakube-redis-master
```

— even though the ConfigMap correctly points at the new Valkey service
(`TerrakubeRedisHostname: <release>-valkey`).

**Cause:** the Deployment/Job loads environment from the ConfigMap **and then** the Secret
(`envFrom` order). On a key collision the **later source wins**, so a stale
`TerrakubeRedisHostname=terrakube-redis-master` left in the API/executor Secret from the
pre‑Valkey chart **overrides** the correct ConfigMap value. Current charts no longer set
`TerrakubeRedisHostname` in the Secret, but Helm does not remove keys it no longer manages,
so the orphan persists across the upgrade.

**Fix:** remove the orphan key from the affected Secret(s); the ConfigMap value then takes
effect. It will not come back, since the current Secret template doesn't define it.

```sh
kubectl patch secret terrakube-api-secrets -n <ns> \
  --type=json -p='[{"op":"remove","path":"/data/TerrakubeRedisHostname"}]'
# repeat for terrakube-executor-secrets (mounted by ephemeral Jobs)
# then restart the api deployment so it re-reads envFrom:
kubectl rollout restart deployment/terrakube-api -n <ns>
```

**To avoid it on future upgrades:** don't keep cache connection values (hostname/port) in
both the ConfigMap and the Secret. Keep the hostname in the ConfigMap only, and ensure the
Secret template (and any external‑secrets source) does not emit `TerrakubeRedisHostname`.
