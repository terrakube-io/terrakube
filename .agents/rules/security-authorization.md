# Security & Authorization Invariants

All development across Terrakube API, Executor, and Client modules MUST strictly adhere to the following security invariants:

## 1. Fail Closed by Default (Zero Implicit Authorization)
* Every endpoint, service, or filter handling authorization MUST fail closed.
* If a security ticket, token, header, or query parameter (e.g., upload ticket, bearer token) is missing, empty, or malformed, the request MUST be immediately rejected with HTTP `401 Unauthorized` or `403 Forbidden`.
* NEVER use fallback default secrets (e.g., hardcoded fallback strings) when cryptographic secrets or signing keys are unconfigured. The service must throw an exception and fail to start or fail closed.
* Security validation toggles (such as `blockPrivateNetworks` for SSRF protection) MUST default to `true` (secure default).

## 2. Context-Aware Scoping for Internal Tokens (`TerrakubeInternal`)
* NEVER use blanket token issuer checks like:
  ```java
  // ANTI-PATTERN: DO NOT DO THIS
  if ("TerrakubeInternal".equals(jwt.getTokenAttributes().get("iss"))) {
      return true;
  }
  ```
* All tokens handled by Terrakube API MUST be classified using `InternalTokenClassifier` into `InternalTokenType`:
  - `NOT_INTERNAL`: Real user or federated identities. Only these tokens can evaluate user group claims or claim platform administrator/superuser roles.
  - `WORKSPACE_EXECUTOR`: Ephemeral step-runner tokens (`iss=TerrakubeInternal` with `workspaceId`). Effective groups MUST return an empty set (`[]`), superuser MUST return `false`, and access MUST be strictly constrained to matching entity UUIDs (`workspaceId`/`organizationId`/`jobId`) via `ExecutorManageJob`, `ExecutorManageJobStep`, `ExecutorManageHistory`, etc.
  - `EXECUTOR_SERVICE`: Backend Executor service daemon (`sub=TerrakubeInternal (EXECUTOR)`). Zero group membership, zero superuser status. Authorized exclusively via dedicated Elide checks (`IsExecutorService`).
  - `REGISTRY_SERVICE`: Backend Registry service daemon (`sub=TerrakubeInternal (REGISTRY)`). Zero group membership, zero superuser status. Authorized exclusively via `IsRegistryService`.
  - `UNKNOWN_INTERNAL`: Any internal token with unrecognized subject, or legacy `TerrakubeInternal (TOKEN)` without `workspaceId`. Fails closed with zero permissions.
* **Service Tokens:** Never grant global superuser status to machine tokens. Microservice interactions must be protected by dedicated Elide checks (`IsExecutorService`, `IsRegistryService`).

## 3. Stateless Public Endpoints & Anti-Harvesting Guardrails
* Unauthenticated endpoints (such as CLI browser redirect bridges under `/app/*`) MUST be purely stateless forwarders:
  1. MUST NOT execute database queries or repository lookups.
  2. MUST NOT disclose internal database UUIDs in URL paths, headers, or payloads.
  3. MUST NOT leak existence oracles (e.g., returning 404 for non-existent entities and 401/403 for existing ones).
* Error responses for unauthorized or invalid resource resolution in the UI MUST use unified error screens (e.g., HTTP 403 "Not Authorized or resource does not exist") to prevent enumerating valid tenant identifiers.

## 4. Child Process & Worker Environment Sanitization
* When spawning external processes (Terraform CLI, OpenTofu, bash scripts, Groovy runners, OPA engines) via `ProcessBuilder` or process launchers:
  1. Child processes MUST NOT inherit sensitive host environment variables.
  2. Spawning dynamic scripting engines (Groovy, Bash, Python) MUST execute out-of-process in an isolated child process rather than in-process sandboxers (e.g., AST customizers) which are vulnerable to reflection bypasses in modern Java.
  3. Explicitly sanitize or clear the process environment map (`clearEnvironment()`) before invocation, passing ONLY an explicit whitelist of safe system variables (`SAFE_SYSTEM_ENV`). Stripping signing secrets (`InternalSecret`, `secretKey`), database credentials, Redis passwords, and cloud provider root secrets is mandatory.
  4. Never inject administrative Spring beans or service clients (`TerrakubeClient`) into script bindings. All external communication must use short-lived, context-scoped tokens over HTTP.
  5. Untrusted job code (Terraform providers, external data sources, custom modules, Groovy/Bash scripts) must never be able to inspect host JVM environment variables or `/proc/$PID/environ`.

## 5. Ephemeral Credential Lifetimes & Guaranteed Teardown
* Tokens written to disk or container filesystems (such as `~/.terraformrc` credentials) MUST have short expirations strictly matching the job duration (e.g., 60 minutes or `jobTimeout`), NEVER long durations like 30 days.
* All generated JWTs MUST include a unique `jti` claim and support immediate revocation: `WORKSPACE_EXECUTOR` step tokens are verified live against the database `Step` row via `ActiveStepTokenValidator` (`stepRepository.findById` + `isTerminalStatus`), while PAT and team tokens are checked against active rows in `pat` and `team_token` (`DexAuthenticationManagerResolver.isTokenActive`).
* Credential files created during workspace setup MUST be scrubbed (overwritten and deleted) inside guaranteed `finally` teardown blocks upon job completion or failure.

## 6. Elide Resource & Field Protection (`@Exclude`)
* Any JPA `@Entity` field containing executable workflows, templates, or internal state machine data (e.g., `Job.tcl`, deprecated `Schedule.tcl`) MUST be annotated with `@Exclude` to prevent callers from modifying or injecting malicious payloads via Elide JSON:API or GraphQL endpoints.
* Elide security check expressions MUST use space-separated words without underscores (e.g. `user is an executor service`).

