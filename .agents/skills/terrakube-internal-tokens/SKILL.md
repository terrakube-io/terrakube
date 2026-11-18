---
name: terrakube-internal-tokens
description: Internal token classification taxonomy, live step-bound token validation, microservice authorization for Executor and Registry, granular Elide security checks, dynamic PID namespace isolation, ephemeral secrets zero-overwrite shredding, and state/context gateway protections.
---

# Terrakube Internal Token Architecture & Microservice Access Control Guide

This guide details the internal token classification taxonomy, live step-bound token validation, microservice permissions for `executor` and `registry`, Elide JSON:API security checks, runtime process isolation, ephemeral secrets lifecycle, and security hardening patterns across Terrakube.

---

## 1. High-Level Architecture & Token Classification Taxonomy

```mermaid
flowchart TD
    subgraph TokenIssuance ["1. Token Minting"]
        ExecDaemon["Executor Backend Daemon\n(sub: TerrakubeInternal (EXECUTOR), no workspaceId)"]
        RegDaemon["Registry Daemon (RegistryTokenService)\n(sub: TerrakubeInternal (REGISTRY), no workspaceId)"]
        Runner["Step Runner Token (WorkspaceSecurityImpl)\n(carries: workspaceId, organizationId, jobId, stepId, jti: jobId-stepId)"]
        UserToken["User / Federated Identity\n(Dex OIDC, PAT, or GitHub Actions JWT)"]
    end

    subgraph TokenClassification ["2. Token Classification (InternalTokenClassifier)"]
        Classifier{"InternalTokenClassifier.classify(claims)"}
        ExecDaemon --> Classifier
        RegDaemon --> Classifier
        Runner --> Classifier
        UserToken --> Classifier

        Classifier -->|iss != TerrakubeInternal| NotInternal["NOT_INTERNAL\n(User/PAT/Federated)"]
        Classifier -->|iss == TerrakubeInternal + workspaceId| WsExecutor["WORKSPACE_EXECUTOR\n(Step Runner)"]
        Classifier -->|iss == TerrakubeInternal + sub=EXECUTOR| SvcExec["EXECUTOR_SERVICE\n(Executor Daemon)"]
        Classifier -->|iss == TerrakubeInternal + sub=REGISTRY| SvcReg["REGISTRY_SERVICE\n(Registry Daemon)"]
        Classifier -->|iss == TerrakubeInternal + unknown/legacy sub| Unknown["UNKNOWN_INTERNAL\n(Fail Closed: 0 Permissions)"]
    end

    subgraph SecurityPipeline ["3. Authentication & Live Revocation"]
        WsExecutor --> ActiveStep{"ActiveStepTokenValidator\n(OAuth2TokenValidator)"}
        ActiveStep -->|step.status in terminal status| TokenRevoked["Reject HTTP 401 / 403\n(Live Step Revocation)"]
        ActiveStep -->|step.status active (running, queue)| TokenAllowed["Token Valid"]

        DexGroup["DexGroupServiceImpl\n(isServiceMember / getEffectiveGroups)"]
        SuperUser["IsSuperUser check"]

        WsExecutor -.->|Deny Groups / Deny Superuser| DexGroup
        SvcExec -.->|Deny Groups / Deny Superuser| DexGroup
        SvcReg -.->|Deny Groups / Deny Superuser| DexGroup
        Unknown -.->|Deny Groups / Deny Superuser| DexGroup

        NotInternal -->|Evaluate user groups & superuser| DexGroup
    end

    subgraph AccessGateways ["4. Resource Authorization & Security Gateways"]
        TokenAllowed --> ElideChecks["Elide Checks (io.terrakube.api.rs.checks.*)"]
        TokenAllowed --> StateGate["StateService\n(Remote state read & flow-type write gate)"]
        TokenAllowed --> ContextGate["JobLogAccessService\n(Job context read & write gate)"]
        TokenAllowed --> VersionGate["JobResolvedVersionController\n(Pinned version mutation gate)"]

        ElideChecks -->|IsExecutorService| AllowExec["Executor Daemon Endpoints"]
        ElideChecks -->|IsRegistryService| AllowReg["Registry Metadata & Tarballs"]
        ElideChecks -->|ExecutorManageJob / ExecutorManageJobStep| AllowRunner["Exact Job/Step UUID Matched Mutation"]
        ElideChecks -->|ReadPrivateKey / ReadAccessToken| DenyRunner["Denied to Runners (Registry Only)"]
    end

    subgraph ChildRuntime ["5. Executor Child Process Hardening"]
        Launcher["ProcessLauncher (unshare)\n(Linux PID namespace isolation)"]
        ProcFs{"Parent /proc/$ppid/environ"}
        Launcher -->|PID Isolation Blocks| ProcFs
        EnvSanitize["clearEnvironment() + SAFE_SYSTEM_ENV\n(Strips InternalSecret, Redis, DB passwords)"]
        Shredder["InternalSecretLoader.shredAndDeleteFile()\n(In-place zero overwrite & deletion)"]
        EphemeralRc["~/.terraformrc\n(Purged in ExecutorJobImpl finally block)"]
    end
```

### Classification Taxonomy (`InternalTokenType`)

All JWT tokens handled by Terrakube API are categorized using `io.terrakube.api.plugin.security.token.InternalTokenClassifier`:

```java
public enum InternalTokenType {
    NOT_INTERNAL,       // Real user identities: Dex OIDC, PAT, or Federated tokens
    WORKSPACE_EXECUTOR, // Per-job runner tokens (iss=TerrakubeInternal, carries workspaceId)
    EXECUTOR_SERVICE,   // Executor backend daemon (iss=TerrakubeInternal, sub=TerrakubeInternal (EXECUTOR), no workspaceId)
    REGISTRY_SERVICE,   // Registry backend daemon (iss=TerrakubeInternal, sub=TerrakubeInternal (REGISTRY), no workspaceId)
    UNKNOWN_INTERNAL    // Legacy or forged internal token (missing/unrecognized subject without workspaceId) -> FAIL CLOSED
}
```

### Classification Rules

| Token Category | Claim Criteria | Target Identity & Privileges |
|---|---|---|
| `NOT_INTERNAL` | `claims == null` OR `iss != "TerrakubeInternal"` | Human users, Personal Access Tokens (PAT), or Federated OIDC (GitHub Actions). Evaluated for user groups and platform administrator roles. |
| `WORKSPACE_EXECUTOR` | `iss == "TerrakubeInternal"` AND non-empty `workspaceId` | Ephemeral step runner tokens passed to Terraform CLI, Bash scripts, or Groovy runners. Scoped to exact `organizationId`, `workspaceId`, `jobId`, `stepId`, with deterministic `jti = jobId-stepId`. |
| `EXECUTOR_SERVICE` | `iss == "TerrakubeInternal"` AND no `workspaceId` AND `sub == "TerrakubeInternal (EXECUTOR)"` | Backend Executor daemon. Zero user groups, zero superuser status. Authorized only via dedicated microservice checks (`IsExecutorService`, `ExecutorManageHistory`). |
| `REGISTRY_SERVICE` | `iss == "TerrakubeInternal"` AND no `workspaceId` AND `sub == "TerrakubeInternal (REGISTRY)"` | Backend Registry daemon. Zero user groups, zero superuser status. Authorized only via dedicated microservice checks (`IsRegistryService`). |
| `UNKNOWN_INTERNAL` | `iss == "TerrakubeInternal"` without `workspaceId` and unrecognized or legacy subject (e.g. `TerrakubeInternal (TOKEN)`) | **Fails closed**. Categorized with zero permissions across all endpoints. |

---

## 2. Live Step-Bound Token Revocation (`ActiveStepTokenValidator`)

To prevent replay or abuse of runner tokens outside the lifetime of their executing step, Terrakube implements live step status validation:

### Implementation Architecture
`ActiveStepTokenValidator` implements Spring Security's `OAuth2TokenValidator<Jwt>` and is plugged into the Nimbus JWT decoder chain in `DexAuthenticationManagerResolver` and `StateService`:

```java
// DexAuthenticationManagerResolver.java
decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
        new JwtTimestampValidator(),
        new ActiveStepTokenValidator(stepRepository)
));
```

### Step Resolution & Terminal Status Rejection
1. **Extraction**:
   - Inspects `stepId` claim directly.
   - If missing, extracts `stepId` from the compound `jti` claim (`jobId-stepId`), where two 36-character UUIDs separated by a hyphen have length $\ge 73$ with the hyphen at index 36.
2. **Database Verification**:
   - Queries `stepRepository.findById(stepUuid)`.
   - Rejects with `invalid_token` if the step does not exist.
3. **Terminal Status Invalidation**:
   - If `step.getStatus()` is in any terminal state, the token is immediately rejected with HTTP 401:
     ```java
     public static boolean isTerminalStatus(JobStatus status) {
         if (status == null) {
             return true;
         }
         return switch (status) {
             case completed, failed, cancelled, rejected, noChanges, notExecuted, NeverExecuted -> true;
             case running, queue, pending, waitingApproval, approved -> false;
             default -> true; // fail-closed on unknown status
         };
     }
     ```
   - **Security Guarantee**: Even if a runner token has 60 minutes of nominal expiration remaining, the moment a step transitions to `completed`, `failed`, or `cancelled`, the token is instantaneously revoked across all Terrakube API endpoints.

---

## 3. Access Controls & Revocation of Blanket Trust

### Blanket Group Membership Revocation (`DexGroupServiceImpl`)
Internal machine tokens (`WORKSPACE_EXECUTOR`, `EXECUTOR_SERVICE`, `REGISTRY_SERVICE`, `UNKNOWN_INTERNAL`) MUST NEVER be granted user group membership:

```java
@Override
public boolean isServiceMember(User user, String group) {
    JwtAuthenticationToken principal = (JwtAuthenticationToken) user.getPrincipal();
    Map<String, Object> tokenAttributes = principal.getTokenAttributes();
    if (InternalTokenClassifier.classify(tokenAttributes) != InternalTokenType.NOT_INTERNAL) {
        log.debug("Internal machine token ({}) cannot have user service group membership for group {}",
                tokenAttributes.get("sub"), group);
        return false; // ALWAYS FALSE for internal machine tokens
    }
    return isMember(user, group);
}

@Override
public Set<String> getEffectiveGroups(User user) {
    JwtAuthenticationToken principal = (JwtAuthenticationToken) user.getPrincipal();
    Map<String, Object> tokenAttributes = principal.getTokenAttributes();
    if (InternalTokenClassifier.classify(tokenAttributes) != InternalTokenType.NOT_INTERNAL) {
        return Collections.emptySet(); // Machine tokens have NO effective user groups
    }
    ...
}
```

### Superuser Privilege Lockdown (`IsSuperUser`)
Machine tokens cannot claim platform superuser status:

```java
@Override
public boolean ok(User user) {
    if (user != null && user.getPrincipal() instanceof JwtAuthenticationToken jwt) {
        Map<String, Object> claims = jwt.getTokenAttributes();
        if (InternalTokenClassifier.classify(claims) != InternalTokenType.NOT_INTERNAL) {
            return false; // UNCONDITIONALLY DENIED
        }
    }
    ...
}
```

> [!WARNING]
> **Anti-Pattern Warning**: Never check only `if ("TerrakubeInternal".equals(jwt.getClaims().get("iss")))`. Always classify the token with `InternalTokenClassifier.classify()` to distinguish between daemon tokens, runner tokens, and forged/unrecognized internal tokens.

---

## 4. Microservice & Granular Elide Security Checks

### 1. Dedicated Microservice Checks
Microservice operations are strictly partitioned using dedicated Elide `@SecurityCheck` components:

* **`IsExecutorService` (`user is an executor service`)**:
  Matches exclusively `InternalTokenType.EXECUTOR_SERVICE`.
* **`IsRegistryService` (`user is a registry service`)**:
  Matches exclusively `InternalTokenType.REGISTRY_SERVICE`.
* **`ExecutorManageHistory` (`executor manage history`)**:
  A `WORKSPACE_EXECUTOR` check validating `workspaceId`, `organizationId`, and `jobId` claims when creating state history records (paired on `History` with `executor manage history OR user is an executor service`).

### 2. Granular Workspace Runner Security Checks (`io.terrakube.api.rs.checks.executor.*`)

Ephemeral runner tokens (`WORKSPACE_EXECUTOR`) are strictly bounded by exact entity UUID matching:

| Check Class | Elide Rule String | Validated Claims vs Entity Attributes |
|---|---|---|
| `ExecutorReadOrganization` | `executor read organization` | `token.organizationId == org.id` |
| `ExecutorReadWorkspace` | `executor read workspace` | `token.workspaceId == workspace.id` |
| `ExecutorReadJob` | `executor read job` | `token.workspaceId == job.workspace.id && token.jobId == job.id` |
| `ExecutorManageJob` | `executor manage job` | Same as read, validates status mutation |
| `ExecutorReadJobStep` | `executor read job step` | `token.jobId == step.job.id && token.stepId == step.id` |
| `ExecutorManageJobStep` | `executor manage job step` | Same as read, allows step status update |
| `ExecutorReadHistory` | `executor read history` | `token.workspaceId == history.workspace.id` |
| `ExecutorManageWorkspaceHistory` | `executor manage workspace history` | `token.workspaceId == workspace.id && token.organizationId == workspace.organization.id` |

### 3. Sensitive Resource Invariants
Runner tokens are explicitly rejected from reading sensitive infrastructure credentials:
* **SSH Private Keys (`ReadPrivateKey`)**: Permitted exclusively to `IsRegistryService`.
* **VCS Access Tokens (`ReadAccessToken`)**: Permitted exclusively to `IsRegistryService`.
* **GitHub App Installation Tokens (`ReadGitHubAppInstallationToken`)**: Permitted exclusively to `IsRegistryService`.
* **Notification Signing Secrets (`ReadNotificationSigningSecret`)**: Unconditionally returns `false` (strictly write-only).
* **Cross-Workspace History or State**: Rejects if entity workspace ID does not match token claim.

### 4. Entity Field Protection (`@Exclude`)
Any JPA `@Entity` field containing executable workflows, templates, or internal state machine data MUST be annotated with `@Exclude`:

```java
// io.terrakube.api.rs.job.Job (and deprecated Schedule.tcl)
@Exclude
@Column(name = "tcl")
private String tcl;
```
This prevents malicious actors from injecting arbitrary workflow YAML templates during Job creation or update via JSON:API or GraphQL.

---

## 5. State & Context Gateway Protections

### 1. State Service Gateway (`StateService`)
The Terraform state storage endpoint enforces multi-layered authorization:

* **Internal Token State Access (`validateInternalTokenStateAccess`)**:
  - `EXECUTOR_SERVICE`: Granted.
  - `UNKNOWN_INTERNAL` and `REGISTRY_SERVICE`: Denied.
  - `WORKSPACE_EXECUTOR`:
    - Direct access: Authorized ONLY for its own `workspaceId` and matching `organizationId`.
    - Cross-workspace write: **Unconditionally denied**.
    - Cross-workspace read (`terraform_remote_state`): Permitted ONLY if the target workspace belongs to the same organization AND either has `globalRemoteState = true` OR lists the caller's workspace ID in `sharedIds`.
* **Step Flow-Type Write Enforcement (`isStepWriteAuthorized`)**:
  - Validates the step flow type via `tclService.getFlowTypeForStep(job, step.getStepNumber())`.
  - State mutation (`GET` / `POST` / `PUT` / `DELETE` to `/tfstate/v1/organization/{organizationId}/workspace/{workspaceId}/backend/terraform.tfstate` in `TerraformStateController`) is restricted strictly for writes (`POST` / `PUT` / `DELETE`) to:
    - `terraformApply`
    - `terraformDestroy`
    - `customScripts`
  - **Security Invariant**: `terraformPlan` steps are strictly prevented from writing state, neutralizing rogue providers or malicious code executing during speculative plans.

### 2. Job Log & Context Access Gateway (`JobLogAccessService`)
* **`EXECUTOR_SERVICE`**: Granted full read and write access for all job contexts and console output.
* **`WORKSPACE_EXECUTOR`**: Strictly bounded to matching `jobId` and `workspaceId`.
* **Federated Identifiers**: Can read job logs through team permissions but are barred from calling `canWriteContext()`.

### 3. Pinned Version Gateway (`JobResolvedVersionController`)
* Pinned Terraform/OpenTofu version registration (`PUT /version/v1/organization/{orgId}/job/{jobId}`) is strictly restricted to `InternalTokenType.EXECUTOR_SERVICE`.
* Runner tokens exposed to user scripts cannot alter pinned tool versions.
* Validates `ActiveStepTokenValidator.isTerminalStatus()` to ensure completed jobs cannot have their version modified.

### 4. Scheduler Reconciliation Gateway (`SchedulerReconciliationAccessService`)
* Triggering scheduler sweeps (`GET` / `POST /admin/v1/scheduler/reconciliation`) requires `EXECUTOR_SERVICE` or a user belonging to the configured `instanceOwner` group.
* `WORKSPACE_EXECUTOR` runner tokens are strictly denied.

---

## 6. Executor Runtime Hardening & Process Isolation

### 1. In-Process Sandboxing Anti-Pattern
In modern Java (Java 21+), `java.lang.SecurityManager` is permanently removed. In-process script sandboxes (e.g. Groovy `SecureASTCustomizer`) cannot prevent reflection attacks:
```groovy
// Bypasses AST denylists via reflection
Class.forName('java.lang.System').getMethod('getenv', [String.class] as Class[]).invoke(null, ['InternalSecret'])
```

### 2. Dynamic Linux PID Namespace Isolation (`ProcessLauncher`)
To prevent untrusted child processes (Terraform providers, Bash scripts, Groovy runners) from inspecting `/proc/$ppid/environ` to read the host JVM's environment, `ProcessLauncher` automatically detects Linux namespace capabilities and prefixes process execution with `unshare`:

```java
// ProcessLauncher.java
if (canExecute("unshare", "--map-root-user", "--user", "--pid", "--fork", "--mount-proc", "true")) {
    return List.of("unshare", "--map-root-user", "--user", "--pid", "--fork", "--mount-proc");
}
```
* **Security Effect**: The child process runs inside an isolated PID namespace with its own mounted `/proc`. The parent JVM PID does not exist inside the namespace, neutralizing procfs environment variable harvesting.

### 3. Environment Variable Sanitization
When spawning external processes, `ProcessLauncher.clearEnvironment()` wipes the process environment map, followed by `BashEngine` and `GroovyEngine` whitelisting ONLY safe system variables (`SAFE_SYSTEM_ENV`):
* **`SAFE_SYSTEM_ENV`**: `PATH`, `HOME`, `USER`, `SHELL`, `TMPDIR`, `LANG`, `LC_ALL`, `TERM`.
* **Stripped Secrets**: `InternalSecret`, database passwords, Redis passwords (`TerrakubeRedisPassword`), cloud provider root keys (`AwsTerraform*`, `AzureTerraform*`, `GcpTerraform*`).
* **Context Injections**: Injects only job-specific variables (`organizationId`, `workspaceId`, `jobId`, `stepId`, `terrakubeApi`) and a short-lived scoped `terrakubeToken` (5 minutes).

### 4. Ephemeral Secrets Lifecycle & Zero-Overwrite File Shredding
* **Startup Ingestion**: `ExecutorSecretEnvironmentPostProcessor` executes at startup (`Ordered.LOWEST_PRECEDENCE`), ingesting ephemeral secrets from `/etc/terrakube/secrets` into Spring's environment as `terrakubeEphemeralSecrets`.
* **In-Place File Shredding (`InternalSecretLoader.shredAndDeleteFile`)**:
  ```java
  try (RandomAccessFile raf = new RandomAccessFile(file, "rws")) {
      byte[] buffer = new byte[4096];
      Arrays.fill(buffer, (byte) 0);
      long remaining = length;
      raf.seek(0);
      while (remaining > 0) {
          int toWrite = (int) Math.min(remaining, buffer.length);
          raf.write(buffer, 0, toWrite);
          remaining -= toWrite;
      }
      raf.getFD().sync();
      raf.setLength(0);
  }
  file.delete();
  ```
  Overwrites file content across its entire length with zeroes, flushes to disk via `getFD().sync()`, truncates to 0 bytes, and deletes the file so secrets never linger on persistent storage.

### 5. Ephemeral Credential Lifetimes & Guaranteed Teardown
* **Terraform CLI Credentials (`~/.terraformrc`)**:
  - Minted with a 60-minute duration matching the job execution window.
  - Carries unique `jti = jobId-stepId`.
  - Cleaned up via `workspaceSecurity.cleanTerraformCredentials()` in the guaranteed `finally` block of `ExecutorJobImpl.java`.
* **HTTP Backend Credentials (`HttpTerraformStateImpl`)**:
  - Mints `TF_HTTP_PASSWORD` with `tokenTtlMinutes` (default `360`, tied to `ExecutorJobMaxDurationMinutes`).
* **Runner Tokens for Scripts**:
  - Groovy and Bash runners receive a scoped `terrakubeToken` with a **5-minute expiration** (`generateAccessToken(5, ...)`).

---

## 7. Registry Authorization & Service Token Patterns

1. **Authorization on Metadata & Tarball Downloads**:
   - `ModuleAuthorizationService`: Always validates module read permissions through `isAuthorized()` before returning module versions, archive downloads, or details. Return HTTP 403 when unauthorized.
2. **Service Token Configuration**:
   - The registry daemon generates tokens using `RegistryTokenService` with `iss="TerrakubeInternal"` and `sub="TerrakubeInternal (REGISTRY)"`.
3. **Cross-Tenant Cache Isolation**:
   - In `ModuleInspectorService`, cache keys MUST include organization ID to prevent cross-tenant cache collisions across organizations.

---

## 8. Verification & Test Suite Reference

Always execute the following test suites after modifying authentication or authorization logic:

### API Token & Step Validator Tests (`api`)
```bash
mvn test -Dtest="InternalTokenSecurityTest,ActiveStepTokenValidatorTest"
```
* **`InternalTokenSecurityTest`**: Verifies 5-tier classification, `IsExecutorService`, `IsRegistryService`, and sensitive credential checks (`ReadPrivateKey`, `ReadAccessToken`, `ReadGitHubAppInstallationToken`).
* **`ActiveStepTokenValidatorTest`**: Verifies compound `jti` parsing, step existence lookups, and immediate revocation on terminal step statuses.

### Executor Security & Isolation Tests (`executor`)
```bash
mvn test -Dtest="WorkspaceSecurityTest,InternalSecretLoaderTest,ExecutorSecretEnvironmentPostProcessorTest,ProcessLauncherSecurityTest,GroovyEngineSecurityTest"
```
* **`WorkspaceSecurityTest`**: Verifies JWT claim construction (`jti = jobId-stepId`, `workspaceId`, `organizationId`).
* **`InternalSecretLoaderTest`**: Verifies directory ingestion and in-place zero-overwrite file shredding.
* **`ExecutorSecretEnvironmentPostProcessorTest`**: Verifies Spring environment injection and precedence.
* **`ProcessLauncherSecurityTest`**: Verifies environment clearing and Linux PID namespace isolation against `/proc/$ppid/environ` harvesting.
* **`GroovyEngineSecurityTest`**: Verifies out-of-process JVM execution and resistance to reflection attacks.
