# Repository code map

This page maps where the code lives inside the Terrakube repository, how a workspace run travels from API creation to executor completion, which classes have the highest fan-in, and where `api` packages are tightly coupled.

For coding conventions and deep subsystem guides, see [AGENTS.md](../../AGENTS.md) and the skills under [`.agents/skills/`](../../.agents/skills/).

## Repository layout and stack

The Maven reactor in [`pom.xml`](../../pom.xml) builds four modules (`api`, `registry`, `executor`, `coverage`) on **Java 25** and **Spring Boot 4.1.2**, alongside the React/TypeScript web console in [`ui/`](../../ui) and Playwright suites in [`e2e/`](../../e2e).

| Directory | Stack / Key libraries | Source layout | Tracked files |
| --- | --- | --- | --- |
| [`api/`](../../api) | Java 25, Spring Boot 4.1.2, Yahoo Elide 7.2.0, Quartz 2.5.2, Fabric8 8.0.0 | 690 `src/main` Java, 309 `src/test` Java, 155 XML (incl. Liquibase) | 1,162 |
| [`executor/`](../../executor) | Java 25, `terraform-spring-boot-starter` 1.9.1, `terrakube-spring-boot-starter` 1.11.0, Groovy 6.0.0 | 108 `src/main` Java, 72 `src/test` Java | 186 |
| [`registry/`](../../registry) | Java 25, JGit 7.8.1, `terrakube-spring-boot-starter` 1.11.0 | 49 `src/main` Java, 23 `src/test` Java | 77 |
| [`ui/`](../../ui) | Node 24, Yarn 4.15.0, React 19.3.1, TypeScript 6.0.3, Ant Design 6.7.0, Vite 8.3.6 | 176 `.ts`, 242 `.tsx`, 2 `.js` under `ui/src/` | 1,718 |
| [`e2e/`](../../e2e) | `@playwright/test` 1.63.0 | 10 `.ts` specs in `e2e/tests/` | 13 |
| [`docs/`](../../docs) | Markdown | Operational runbooks, template notes, and contributor docs | 14 |
| [`ephemeral-executor-config/`](../../ephemeral-executor-config) | Kubernetes YAML | `service_account.yml`, `rbac_role.yml`, `rbac_role_binding.yml` | 3 |
| [`scripts/`](../../scripts) & [`.devcontainer/`](../../.devcontainer) | Shell, Docker Compose, SQL | Local stack (`*.localhost`), Dex/LDAP, Valkey, PostgreSQL, Silo/MinIO | 27 |

Cloud storage backends currently live inside each service rather than a shared module:
- `api/src/main/java/io/terrakube/api/plugin/storage/` (`aws`, `azure`, `gcp`, `local`)
- `executor/src/main/java/io/terrakube/executor/plugin/tfstate/` (`aws`, `azure`, `gcp`, `http`, `local`)
- `registry/src/main/java/io/terrakube/registry/plugin/storage/` (`aws`, `azure`, `gcp`, `local`)

## Where things live inside each service

### API (`api/src/main/java/io/terrakube/api/`)

The 690 production Java files in `api` split into three top-level packages (`ServerApplication.java` at root):

1. **`rs/` (231 files)** — Yahoo Elide JSON:API and GraphQL resources, security checks, and lifecycle hooks:
   - Domain entities & enums (64 files across 29 subpackages): `Organization`, `Workspace`, `Project`, `ProjectAccess`, `Access`, `Team`, `Job`, `Step`, `Address`, `Template`, `Variable`, `Globalvar`, `Module`, `Provider`, `Vcs`, `Ssh`, `Webhook`, `RepoWebhook`, `NotificationConfiguration`, `NotificationOutbox`, `PolicySet`, `PolicyAttachment`, `PolicyExemption`, `PolicyEvaluation`, `PolicyOverride`, `WorkspaceRunTrigger`, `RunTriggerEvent`, `Pat`, `Group`, `Federated`, etc.
   - `rs/checks/*` (140 files across 26 subpackages): `@SecurityCheck` `OperationCheck<T>` beans such as `TeamViewWorkspace`, `TeamManageWorkspace`, `TeamApproveJob`, `ExecutorManageJob`, `ExecutorManageJobStep`, `ExecutorManageHistory`, `IsExecutorService`, `IsRegistryService`, `UserBelongsOrganization`, and `MembershipService`.
   - `rs/hooks/*` (27 files across 11 subpackages): Elide `LifeCycleHook<T>` classes such as `JobManageHook`, `JobApprovalHook`, `JobRunRightsHook`, `JobBranchPolicyHook`, `JobVariablesHook`, `JobNotificationHook`, `WorkspaceManageHook`, `WebhookManageHook`, `WebhookEventManageHook`, and `WorkspaceRunTriggerHook`.
2. **`plugin/` (409 files)** — Business logic, schedulers, HTTP controllers, security, and integrations:
   - `plugin/scheduler/` (83 files): Quartz scheduling (`ScheduleJobService`, `ScheduleJob`, `ScheduleJobTrigger`), Redis `ExecutionLease`, TCL resolution (`TclService`), dispatch (`ExecutorService`, `PersistentExecutorService`, `EphemeralExecutorService`), dispatch retries, inactive job sweeps, reconciliation (`JobReconciliationSweep`), spool recovery (`SpoolRecoveryService`), run triggers (`RunTriggerDispatchService`, `WorkspaceGraphValidationService`), and webhook sync.
   - `plugin/security/` (20 files): OIDC/JWT auth (`DexWebSecurityAdapter`, `DexAuthenticationManagerResolver`, `ActiveStepTokenValidator`), `InternalTokenClassifier`, `RbacService` / `RbacV2Service`, `GroupService`, `AuthenticatedUser`, `StateService`, `JobLogAccessService`, `EncryptionService`, and `GenericAuditFields` / `JobApprovalService`.
   - `plugin/state/` (64 files): Terraform CLI Remote Execution API (`/remote/tfe/v2/` in `RemoteTfeController` and `RemoteTfeService`).
   - `plugin/vcs/` (58 files): Incoming VCS webhooks (`WebHookController`, `WebhookService`, `RepoWebhookService`), OAuth callbacks, PR comments (`PrCommentService`), token services, and GitHub/GitLab/Bitbucket/Azure DevOps providers.
   - `plugin/storage/` (19 files), `plugin/context/` (10 files, `/context/v1`), `plugin/logs/` (8 files, `/logs`), `plugin/streaming/` (9 files), `plugin/subscription/` (5 files), `plugin/notification/` (28 files), `plugin/policy/` (6 files), `plugin/execution/` (10 files), `plugin/token/` (21 files, incl. `DynamicCredentialsService`, PAT, team tokens, and CLI login).
3. **`repository/` (49 files)** — Spring Data JPA repositories (`JobRepository`, `WorkspaceRepository`, `StepRepository`, etc.).

### Executor (`executor/src/main/java/io/terrakube/executor/`)

108 production Java files organized by execution responsibility:
- `service/mode/`: `OnlineModeServiceImpl` (`POST /api/v1/terraform-rs` for persistent runners) and `BatchModeServiceImpl` (ephemeral Kubernetes Jobs reading `EphemeralJobData`).
- `service/executor/`: `ExecutorJobImpl` (orchestrates workspace setup, status updates, execution, and `finally` teardown), `ExecutorCapacityGate`, and `JobExecutionWatchdog` (Redis heartbeat).
- `service/workspace/`: `SetupWorkspaceImpl` (Git/tarball fetch) and `WorkspaceSecurityImpl` (scoped `~/.terraformrc` lifecycle).
- `service/terraform/`: `TerraformExecutorServiceImpl`, `PlanStructuredOutputService`, `ApplyStructuredOutputService`, `TerraformJsonEventParser`, `StructuredOutputPersistenceQueue`, `JobContextService`, `BinaryCacheRecoveryService`, `ContextSpoolService`, `RunVariablesFile`, and `BackendConfigArguments`.
- `service/opa/`: `OpaExecutorServiceImpl` and `OpaBinaryService`.
- `service/scripts/`: `ScriptEngineService`, `ProcessLauncher` (environment sanitization and PID namespace isolation), `BashEngine`, `GroovyEngine`, and `GroovyProcessRunner`.
- `service/logs/` & `service/status/`: `LogsServiceRedis`, `LogsServiceApi`, `StepOutputBuffer`, `StepOutputArchiveService`, and `UpdateJobStatusImpl`.
- `plugin/tfstate/`: `TerraformState` implementations (`aws`, `azure`, `gcp`, `http`, `local`).

### Registry (`registry/src/main/java/io/terrakube/registry/`)

49 production Java files serving the Terraform Module and Provider Registry protocol:
- `controller/`: `WellKnownWebServiceImpl` (`/.well-known/terraform.json`), `ModuleWebServiceImpl`, `ProviderWebServiceImpl`, `ReadMeWebServiceImpl`.
- `service/`: `ModuleServiceImpl`, `ModuleAuthorizationService`, `ModuleDownloadTicketService`, `ModuleInspectorService`, `ProviderServiceImpl`, `GitServiceImpl`, `RegistryTokenService`, `ReadMeServiceImpl`.
- `plugin/storage/`: `StorageService` implementations (`aws`, `azure`, `gcp`, `local`).

### UI (`ui/src/`)

420 `.ts` / `.tsx` / `.js` files split across:
- `modules/` (112 files): Newer domain-driven modules (`api`, `apiDocs`, `organizations`, `permissions`, `projects`, `token`, `user`, `utils`, `workspaces`).
- `domain/` (229 files): Core views (`Home/App.tsx` router, `Jobs`, `Workspaces`, `Settings`, `Modules`, `Providers`, `Notifications`, `Organizations`, `Login`).
- `components/` (97 files): Reusable UI primitives (`display`, `feedback`, `forms`, `layout`, `modals`, `navigation`, `settings`).
- `hooks/`, `config/`, `context/`, `reducers/`, `store/`, `types/`.

## End-to-end run path

Every workspace run follows the same core dispatch pipeline regardless of how it starts:

```text
Entrypoint (Elide POST /api/v1/job | RemoteTfeService | WebHookController | ScheduleJobTrigger | RunTriggerDispatchService)
  │
  ▼
ScheduleJobService.createJobContext(job)  ──▶  Quartz Job: ScheduleJob (30s trigger + immediate fire)
  │
  ▼
ScheduleJob.execute()
  ├─ Acquire Redis ExecutionLease (job-execution-lock:<jobId>, 90s TTL, renewed every 30s)
  ├─ Check workspace lock, per-workspace ordering (or plan-only bypass), & 3-tier global FIFO ladder
  ├─ TclService.initJobConfiguration(job) + TclService.getNextFlow(job)
  └─ ExecutorService.execute(...) ──▶ loadOtherEnvironmentVariables() (DynamicCredentialsService)
        │                              ──▶ lease.claimDispatch(stepId)
        ├─ Persistent mode ──▶ PersistentExecutorService ──▶ POST <executor>/api/v1/terraform-rs (202)
        └─ Ephemeral mode  ──▶ EphemeralExecutorService  ──▶ K8s batch/v1 Job (EphemeralJobData)
  │
  ▼ (lease released in finally)
Executor (OnlineModeServiceImpl / BatchModeServiceImpl)
  └─ ExecutorJobImpl.createJob()
        ├─ SetupWorkspaceImpl + UpdateJobStatusImpl (PATCH Job/Step -> running)
        ├─ TerraformExecutorServiceImpl / OpaExecutorServiceImpl / ScriptEngineService
        │     ├─ Live logs ──▶ LogsServiceRedis / LogsController & StepOutputBuffer
        │     ├─ Structured output ──▶ StructuredOutputPersistenceQueue ──▶ POST /context/v1/{jobId}/patch
        │     └─ State ──▶ TerraformState (HTTP /tfstate/v1/... or cloud backend)
        ├─ UpdateJobStatusImpl.setCompletedStatus() (PATCH Step & Job -> pending / waitingApproval / completed / failed)
        └─ Release ExecutorCapacityGate + publish Redis doorbell (terrakube:executor-available)
```

Key files along this path:
- Job creation hooks: [`JobManageHook.java`](../../api/src/main/java/io/terrakube/api/rs/hooks/job/JobManageHook.java), [`JobRunRightsHook.java`](../../api/src/main/java/io/terrakube/api/rs/hooks/job/JobRunRightsHook.java), [`JobApprovalHook.java`](../../api/src/main/java/io/terrakube/api/rs/hooks/job/JobApprovalHook.java), [`RemoteTfeService.java`](../../api/src/main/java/io/terrakube/api/plugin/state/RemoteTfeService.java)
- Scheduler & dispatch: [`ScheduleJobService.java`](../../api/src/main/java/io/terrakube/api/plugin/scheduler/ScheduleJobService.java), [`ScheduleJob.java`](../../api/src/main/java/io/terrakube/api/plugin/scheduler/ScheduleJob.java), [`TclService.java`](../../api/src/main/java/io/terrakube/api/plugin/scheduler/job/tcl/TclService.java), [`ExecutorService.java`](../../api/src/main/java/io/terrakube/api/plugin/scheduler/job/tcl/executor/ExecutorService.java)
- Executor entry & runner: [`OnlineModeServiceImpl.java`](../../executor/src/main/java/io/terrakube/executor/service/mode/online/OnlineModeServiceImpl.java), [`BatchModeServiceImpl.java`](../../executor/src/main/java/io/terrakube/executor/service/mode/batch/BatchModeServiceImpl.java), [`ExecutorJobImpl.java`](../../executor/src/main/java/io/terrakube/executor/service/executor/ExecutorJobImpl.java), [`TerraformExecutorServiceImpl.java`](../../executor/src/main/java/io/terrakube/executor/service/terraform/TerraformExecutorServiceImpl.java), [`UpdateJobStatusImpl.java`](../../executor/src/main/java/io/terrakube/executor/service/status/UpdateJobStatusImpl.java)

## High-fan-in classes in `api`

Across the 690 Java files in `api/src/main/java`, these classes have the highest incoming import counts. Changes to their signatures, fields, or persistence behavior ripple broadly:

| Import count | Class | Role |
| ---: | --- | --- |
| 93 | `io.terrakube.api.rs.workspace.Workspace` | Core tenant/execution unit; touched by checks, hooks, VCS, TFE, scheduler, triggers |
| 91 | `io.terrakube.api.plugin.security.user.AuthenticatedUser` | Principal resolution & superuser checks across nearly all `@SecurityCheck` classes |
| 88 | `io.terrakube.api.rs.job.Job` | Central run state machine; shared across hooks, Quartz, TFE, notifications, policies |
| 63 | `io.terrakube.api.rs.team.Team` | Org-level RBAC matrix (`role` + 12 permission flags) |
| 62 | `io.terrakube.api.plugin.security.groups.GroupService` | Group membership & internal-token group suppression |
| 59 | `io.terrakube.api.plugin.security.rbac.RbacService` | Permission evaluation for workspaces, projects, and orgs |
| 58 | `io.terrakube.api.rs.checks.membership.MembershipService` | Shared helper aggregating user/service/federated membership |
| 50 | `io.terrakube.api.rs.job.JobStatus` | Run lifecycle enum (`pending`, `queue`, `running`, `waitingApproval`, `approved`, etc.) |
| 45 | `io.terrakube.api.rs.Organization` | Top-level tenant entity |
| 35 | `io.terrakube.api.rs.project.Project` | Mid-tier grouping & RBAC scope (`ProjectAccess`) |
| 33 | `io.terrakube.api.plugin.security.audit.GenericAuditFields` | Mapped superclass inherited by 33 Elide entities |

## Cross-package coupling and cycles in `api`

Because Elide binds entities (`rs/`), security checks (`rs/checks/`), and lifecycle hooks (`rs/hooks/`) directly to Spring services (`plugin/`), `api` has dense bidirectional coupling between `rs` and `plugin`:

- `rs` -> `plugin`: 265 import statements across 143 files
- `plugin` -> `rs`: 435 import statements across 129 files
- `rs` -> `repository`: 11 import statements across 9 files (mostly `rs/hooks/*` and `rs/checks/*`)
- `repository` -> `rs`: 88 import statements across 49 files

At the leaf-package level, 89 packages across `io.terrakube.api.rs.*`, `io.terrakube.api.plugin.*`, and `io.terrakube.api.repository` form a single strongly connected component. Common cycle patterns to be aware of when refactoring:

1. **Entities <-> Audit/Security (`rs.<entity>` <-> `plugin.security.audit`)**:
   - `Job` (and 32 other `rs` entities) extends `plugin.security.audit.GenericAuditFields`, while `JobApprovalService` in `plugin.security.audit` imports `rs.job.Job`.
2. **Elide checks <-> Security services (`rs.checks.*` <-> `plugin.security.*`)**:
   - Over 100 `@SecurityCheck` classes import `AuthenticatedUser`, `GroupService`, `RbacService`, and `InternalTokenClassifier`, while `RbacService`, `DexGroupServiceImpl`, `ActiveStepTokenValidator`, and `JobLogAccessService` import `Team`, `ProjectAccess`, `Access`, `Workspace`, `Project`, and `JobStatus`.
3. **Lifecycle hooks <-> Domain services (`rs.<entity>` -> `rs.hooks.*` -> `plugin.*` -> `rs.<entity>`)**:
   - `Job` -> `JobManageHook` -> `ScheduleJobService` -> `Job`
   - `Job` -> `JobApprovalHook` -> `JobRunRights` -> `MembershipService` / `Workspace` / `Project` / `Team`
   - `Workspace` -> `WorkspaceManageHook` -> `WebhookService` / `SoftDeleteService` -> `Workspace` / `JobStatus`
   - `WorkspaceRunTrigger` -> `WorkspaceRunTriggerHook` -> `WorkspaceGraphValidationService` -> `WorkspaceRunTrigger`

When adding new features, keep Quartz job orchestration, external HTTP calls, and multi-repository transactions in `plugin/` rather than adding new `repository` lookups inside `rs/` entities, and ensure any `PRECOMMIT` vs `POSTCOMMIT` hook respects transaction visibility (see [`.agents/skills/terrakube-elide-api/SKILL.md`](../../.agents/skills/terrakube-elide-api/SKILL.md)).
