# Phase 4 Implementation Review: UI, Provenance, & Seeded Graph Scenarios

- **Target Issue:** [Issue #3520: feat: High-Level Architecture Design: Workspace Dependency Graph & Run Triggers in Terrakube](https://github.com/terrakube-io/terrakube/issues/3520)
- **Target Comments:**
  - [Comment #5609267925](https://github.com/terrakube-io/terrakube/issues/3520#issuecomment-5609267925) (Phase 4 UI Architecture & Tests)
  - [Comment #5610097015](https://github.com/terrakube-io/terrakube/issues/3520#issuecomment-5610097015) (DELETE Content-Type Fix & Filter Test)
  - [Comment #5610788157](https://github.com/terrakube-io/terrakube/issues/3520#issuecomment-5610788157) (Codespaces & PostgreSQL E2E Verification)
  - [Comment #5610903195](https://github.com/terrakube-io/terrakube/issues/3520#issuecomment-5610903195) (Browser Verification & Screenshots)
  - [Comment #5626474586](https://github.com/terrakube-io/terrakube/issues/3520#issuecomment-5626474586) (Seeded Graph Dataset & Scenarios)
- **Reviewed Commits:**
  - [`85b89a6d`](https://github.com/klinux/terrakube/commit/85b89a6d3) (`feat(ui): manage run triggers from the workspace`)
  - [`d06ab06f`](https://github.com/klinux/terrakube/commit/d06ab06f3) (`feat(ui): show what triggered a run`)
  - [`f6110d01`](https://github.com/klinux/terrakube/commit/f6110d014) (`test(ui): cover the run trigger page`)
  - [`9ca8ef16`](https://github.com/klinux/terrakube/commit/9ca8ef160) (`fix(ui): drop the content type from the run trigger delete`)
  - [`6346bb1d`](https://github.com/klinux/terrakube/commit/6346bb1d1) (`feat(api): seed a run trigger dependency graph in the demo data`)
  - [`a4f1491a`](https://github.com/klinux/terrakube/commit/a4f1491ac) (`test(api): exercise the engine over the seeded graph`)
- **Branch:** `klinux/feat/workspace-run-trigger-phase1`
- **Review Date:** September 11, 2026
- **Verdict:** 🚀 **EXCELLENT / APPROVED FOR MERGE**

---

## 1. Executive Summary & Verdict

Phase 4 concludes the Workspace Run Triggers feature by delivering a production-ready UI, run-level provenance tracking, a seeded demo graph scenario suite, and end-to-end integration tests.

The implementation is **clean, robust, and thoughtful**:
1. **RBAC-Aligned Split Screen:** The Run Triggers page divides incoming ("Runs after") and outgoing ("Triggers") edges according to Terrakube's permission model. By making the "Triggers" table read-only and directing users to the destination workspace, the UI avoids presenting actions that the API would reject.
2. **Single-Query Disjunctive Filter:** The UI fetches both dependency directions in a single HTTP request using Elide's OR filter (`sourceWorkspace.id==<id>,destinationWorkspace.id==<id>`) and includes all related workspace and template entities, preventing N+1 queries.
3. **Run Provenance:** Replaces the legacy hardcoded "Triggered via UI" label with dynamic `job.data.attributes.via` and adds an alert banner linking directly to the upstream run.
4. **Seeded Graph Scenarios (`simple-trigger`):** Introduces 38 workspaces and 28 edges in `changelog-demo.xml` modeling all canonical graph topologies (fan-out, chain, fan-in, diamond, disabled, override, standalone, deep cascade) using `terrakube-docker-compose` (`null_resource` with 30s sleep) for cloud-free local verification.
5. **Non-Destructive Test Isolation:** Switched `@AfterEach` in test suites from blanket `deleteAll()` to selective deletion of test-created rows, preserving shared demo data.

All frontend and backend test suites pass with **0 failures**:
- **UI Tests (`RunTriggers.test.tsx`):** 6 passed, 0 failed.
- **Trigger Test Suite (6 test classes):** 63 passed, 0 failed.
- **Full API Module:** 1,103 passed, 0 failed.

---

## 2. Architectural Highlights & Wins

### 1. Permission-Aligned Split Screen (`ui/src/domain/Workspaces/RunTriggers.tsx`)
- Configuring a trigger requires manage rights on the **destination workspace** (the workspace that will run).
- **"Runs after" (Destination = current workspace):** Fully configurable (Add source workspace, Enabled toggle, and Delete) because the user already has manage rights on this workspace.
- **"Triggers" (Source = current workspace):** Read-only, showing which workspaces are triggered when this workspace changes state, with direct links to those workspaces. Modifying those triggers requires manage rights on those destination workspaces.
- This design completely eliminates false affordances and aligns UI interactions with backend authorization checks.

### 2. Single Round-Trip Elide Disjunctive Filter
```ts
axiosInstance.get("runTrigger", {
  params: {
    include: "sourceWorkspace,destinationWorkspace,template",
    "filter[runTrigger]": `sourceWorkspace.id==${workspaceId},destinationWorkspace.id==${workspaceId}`,
  },
})
```
- In Elide JSON:API syntax, a comma in a filter represents a logical `OR`.
- The page fetches all connected edges in one round-trip and splits them client-side into `incoming` and `outgoing`.
- Pinned and verified on both H2 and PostgreSQL with a dedicated backend test (`filteringByEitherEndReturnsBothDirections`).

### 3. Provenance Visualization on Job Details (`ui/src/domain/Jobs/Details.tsx`)
- Resolves the legacy hardcoded heading `<h2 style={{ display: "inline" }}>Triggered via UI</h2>` to use `job.data.attributes.via || "UI"`.
- Adds an informational alert linking to the upstream job:
  > *"This run started because run #X on workspace-name changed state. It is Y triggers deep in the chain."*
- Gracefully handles pruned upstream jobs (`.catch()` fallback renders `run #X changed state.` without broken links).

### 4. Seeded Topologies (`api/src/main/resources/db/changelog/demo-data/run-trigger-scenarios.xml`)
Seeded in `simple-trigger` organization under `changelog-demo.xml`:
| Scenario | Structure | Purpose |
|---|---|---|
| `fanout-hub` → `fanout-app-1..5` | 1 triggers 5 | Verified in integration test: 1 apply wakes 5 consumers. |
| `chain-01..05` | 5 in sequence | Verified in integration test: each apply advances the chain by exactly one step. |
| `fanin-net`, `fanin-dns` → `fanin-app` | 2 trigger 1 | Validates multiple upstreams converging on a single consumer. |
| `diamond-root` → left/right → `diamond-join` | Diamond | Verifies that both branches trigger the join independently without arbitrary debounce. |
| `disabled-upstream` → `disabled-downstream` | `enabled=false` | Verifies toggle functionality and disabled edge no-op. |
| `override-upstream` → `override-downstream` | Template override | Verifies trigger template override over workspace default template. |
| `standalone-01..04` | No edges | Provides clean empty states for UI testing. |
| `cascade-01..12` | 12 in sequence | Allows verification of the runtime `maxCascadeDepth` boundary (default 10). |

---

## 3. Bug Fixes & Pre-existing Issues Identified

### 1. HTTP DELETE with `Content-Type` Header Fix (`9ca8ef16`)
- **Root Cause:** Elide answers HTTP 400 Bad Request to a bodyless HTTP DELETE request that declares a `Content-Type: application/vnd.api+json` header.
- **Resolution:** Removed the header from `axiosInstance.delete("runTrigger/${id}")` and added regression test `deleteDeclaringAContentTypeIsRejected`.
- **Tech-Debt Note:** As noted by the contributor, `Schedules.tsx` and `Variables.tsx` also set this header on DELETE requests. While axios may strip the header on bodyless requests in some browser versions, cleaning this up across the remaining components in a separate PR will prevent future regressions.

### 2. MinIO Custom Endpoint Checksum Conflict (Devcontainer Issue)
- In `StorageTypeAutoConfiguration.java:L104-107`, AWS SDK 2.x throws an `IllegalStateException` under `-s MINIO` because checksum validation is configured both in `S3Configuration` (`.checksumValidationEnabled(...)`) and on the client builder level (`.requestChecksumCalculation(...)`).
- This was an existing issue on `main` from PR #3473. The contributor properly documented the workaround (`-s LOCAL`) and kept the branch cleanly scoped.

---

## 4. Minor UX / Polish Recommendations (Non-blocking)

1. **Filter Already-Added Sources in the Modal Picker**:
   In `RunTriggers.tsx:loadPickerData`, `workspaces` is filtered to exclude the current workspace (`item.id !== workspaceId`). Filtering out workspaces that are already in `incoming`:
   ```ts
   const availableWorkspaces = workspaces.filter(
     (w) => w.id !== workspaceId && !incoming.some((r) => r.workspaceId === w.id)
   );
   ```
   would prevent users from selecting duplicate edges that fail with HTTP 409 Conflict upon submission.
2. **Organization-Level Workspace Pagination**:
   In enterprise environments with hundreds of workspaces, `GET organization/${organizationId}/workspace` in `loadPickerData` could hit default JSON:API page size limits. In a future update, adding server-side search (`filter[workspace]=name==*${query}*`) will ensure scalability.

---

## 5. Verification Matrix

| Area | Component | Test Command | Result |
|---|---|---|---|
| **UI Components** | `RunTriggers.test.tsx` | `yarn test src/domain/Workspaces/__tests__/RunTriggers.test.tsx` | ✅ 6 / 6 Passed |
| **Trigger Logic & Integration** | `WorkspaceRunTriggerTests`, `RunTriggerDispatchIntegrationTest`, `WorkspaceGraphValidationServiceTests`, etc. | `mvn test -Dtest=WorkspaceRunTriggerTests,WorkspaceGraphValidationServiceTests,RunTriggerDispatchIntegrationTest,RunTriggerDispatchServiceTest,RunTriggerJobWriterTest,JobReconciliationServiceTest` | ✅ 63 / 63 Passed |
| **Full API Regression** | Complete API suite | `mvn test` in `terrakube/api` | ✅ 1,103 / 1,103 Passed |
| **Live Database & UI** | Codespaces (Postgres) | Click-through of source, destination, delete, and link resolution | ✅ Verified with Screenshots |

---

## 6. Conclusion

Phase 4 completes the Workspace Run Triggers feature with excellence. The implementation is secure, well-tested, adheres strictly to Terrakube's architectural rules, and provides a polished user experience.

**Branch `klinux/feat/workspace-run-trigger-phase1` is approved and ready to be merged!**
