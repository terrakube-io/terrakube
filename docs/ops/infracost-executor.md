# Infracost Executor Integration

This document describes configuration, security boundaries, binary caching, and OPA cost-data injection for the Infracost integration in `terrakube-executor`.

## Configuration Properties

Operator configuration is bound through `InfracostExecutorProperties` (`io.terrakube.executor.infracost`) in `application.properties`:

| Property | Env / Placeholder | Default | Purpose |
| --- | --- | --- | --- |
| `io.terrakube.executor.infracost.default-version` | `${ExecutorInfracostVersion:0.10.50}` | `0.10.50` | Infracost CLI release version when `INFRACOST_VERSION` is not set. |
| `io.terrakube.executor.infracost.timeout-seconds` | `${ExecutorInfracostTimeout:180}` | `180` | Shared deadline (seconds) covering binary resolution/download and `infracost breakdown` execution. |
| `io.terrakube.executor.infracost.default-currency` | `${ExecutorInfracostCurrency:USD}` | `USD` | Default output currency when `INFRACOST_CURRENCY` is not set. |
| `io.terrakube.executor.infracost.pricing-api-endpoint` | `${ExecutorInfracostPricingEndpoint:}` | `""` (empty) | Operator-only `INFRACOST_PRICING_API_ENDPOINT` override passed solely to the `infracost` CLI process. |
| `io.terrakube.executor.infracost.enable-dashboard` | `${ExecutorInfracostEnableDashboard:false}` | `false` | Operator-only `INFRACOST_ENABLE_DASHBOARD` setting passed solely to the `infracost` CLI process. |
| `io.terrakube.executor.infracost.context-max-bytes` | `${ExecutorInfracostContextMaxBytes:49152}` | `49152` | Maximum JSON byte size persisted per step under `/context` before trimming the resource list. |
| `io.terrakube.executor.infracost.max-context-resources` | `${ExecutorInfracostMaxContextResources:50}` | `50` | Soft cap on `resources` entries included in the step `/context` summary when trimming. |

Per-job environment variables (read from the workspace/job environment, not workspace HCL):
- `INFRACOST_API_KEY`: required to trigger `infracost breakdown` during plan/policy runs (unless omitted, in which case cost estimation is skipped).
- `INFRACOST_VERSION`: optional semver override (`^v?[0-9]+\.[0-9]+\.[0-9]+$`).
- `INFRACOST_CURRENCY`: optional ISO-like currency code (`USD`, `EUR`, etc.).

## Security & Secret Isolation

- **Subprocess scrubbing (`InfracostEnvSanitizer`)**: `INFRACOST_API_KEY`, `INFRACOST_PRICING_API_ENDPOINT`, and `INFRACOST_ENABLE_DASHBOARD` are stripped from the subprocess environments of Terraform/OpenTofu (`TerraformExecutorServiceImpl`, `PlanStructuredOutputService`), Bash (`BashEngine`), Groovy (`GroovyEngine`), and OPA (`OpaExecutorServiceImpl`), without mutating `terraformJob.getEnvironmentVariables()`.
- **Operator-only pricing & dashboard controls**: Workspace/environment values for `INFRACOST_PRICING_API_ENDPOINT` and `INFRACOST_ENABLE_DASHBOARD` are ignored; only `InfracostExecutorProperties` values are forwarded to `infracost`.
- **`plan.json` precedence & disabled `infracost.yml` auto-detection**: When `plan.json` exists in the working directory, `infracost breakdown` always targets `--path plan.json` and never auto-discovers `infracost.yml` configuration files.
- **Process tree cleanup**: On timeout or cancellation without Linux `unshare`, `ProcessLauncher.destroyForcibly()` terminates `Process.descendants()` in deepest-first order before destroying the root process.

## Data Egress (What Leaves the Cluster)

When cost estimation runs, the `infracost breakdown` subprocess sends Terraform plan metadata (resource types, SKUs/instance sizes, regions, and usage quantities) to the configured pricing API (`https://pricing.api.infracost.io` by default, or `io.terrakube.executor.infracost.pricing-api-endpoint` when pointing at a self-hosted Cloud Pricing API).

- Workspace secrets, cloud provider credentials, and `TF_VAR_*` values are scrubbed from the subprocess environment (`InfracostEnvSanitizer`).
- Dashboard uploads (`--sync-usage-file` / share links) remain disabled unless the operator explicitly sets `io.terrakube.executor.infracost.enable-dashboard=true`.

## Binary Resolution & SHA-256 Cache Verification

1. **`PATH` version guard**: If `infracost` already exists on `PATH` (for example baked into the executor image), `InfracostBinaryService` runs `infracost --version` and reuses it only when it reports the requested version.
2. **Local + storage SHA-256 verification**: Cached binaries under `<user.home>/.terrakube/bin/infracost/<version>/<os>_<arch>/infracost` and in state storage (`infracost/<version>/<os>_<arch>/infracost`) carry `.sha256` sidecar digests (`infracost.sha256`). In-process hash checks are memoized per `(path, lastModified, length)` in `verifiedLocalHashes`; on mismatch or missing digest, the corrupted copy is deleted and re-downloaded from GitHub Releases under the shared `Instant deadline`.
   - **Storage bucket trust boundary**: Restoring from state storage verifies the binary against the `.sha256` sidecar from the same bucket, which catches corruption or truncated uploads. Because anyone with write access to the bucket could replace both files, the state storage bucket is part of the executor's trust boundary (same as the Terraform and OPA binary caches) and must be restricted via IAM/RBAC.
3. **Graceful shutdown**: `InfracostExecutorServiceImpl` shuts down its virtual-thread executor (`Executors.newVirtualThreadPerTaskExecutor()`) on bean destruction (`@PreDestroy`).

## Air-Gapped & Pre-Baked Deployments

For clusters without outbound access to `github.com` (or `pricing.api.infracost.io`), you can pre-bake the CLI binary using either approach:

- **Bake onto `PATH` in the executor image**: Install `infracost` (e.g., `/usr/local/bin/infracost`) matching `io.terrakube.executor.infracost.default-version` (`ExecutorInfracostVersion`). `InfracostBinaryService` verifies `infracost --version` and uses it without touching state storage or GitHub Releases.
- **Pre-seed local or storage cache**: Populate `<user.home>/.terrakube/bin/infracost/<version>/<os>_<arch>/infracost` along with its `infracost.sha256` sidecar (or upload both objects to `infracost/<version>/<os>_<arch>/` in the state storage bucket).

Pair pre-baked binaries with a self-hosted `io.terrakube.executor.infracost.pricing-api-endpoint` (`ExecutorInfracostPricingEndpoint`) if `pricing.api.infracost.io` is also unreachable.

## OPA Integration & `/context` Payloads

- **Local OPA input**: `InfracostExecutorServiceImpl` writes the full parsed summary (`infracost-opa-data.json`) in the working directory with numeric `BigDecimal` totals (`totalHourlyCost`, `totalMonthlyCost`, `diffTotalMonthlyCost`, `pastTotalMonthlyCost`) and per-resource entries for `data.infracost` evaluation.
- **Step-scoped `/context` payload**: Cost summaries are written to Terrakube job context under `infracost/<stepId>`, bounded to `contextMaxBytes` / `maxContextResources` (marking `truncated: true` if trimmed so large plans do not blow job context size limits).
- **Headless OPA (`policyEvaluation`)**: Both post-plan evaluation (`TerraformExecutorServiceImpl`) and standalone `policyEvaluation` steps (`OpaExecutorServiceImpl.evaluateJob`) invoke `InfracostExecutorService.evaluateCost(...)` when `INFRACOST_API_KEY` is configured, surfacing warnings to the step console via `[WARN] ...`.
- **Hard-mandatory cost policies**: Because cost estimation fails open, a workspace user can set `INFRACOST_ENABLED=false` or configure an invalid `INFRACOST_API_KEY`, leaving `data.infracost` undefined or `data.infracost.status == "ERROR"`. Any hard-mandatory OPA cost policy meant to strictly enforce budgets must explicitly deny when `not data.infracost` or `data.infracost.status != "SUCCESS"`.
