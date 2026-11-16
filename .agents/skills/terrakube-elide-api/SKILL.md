---
name: terrakube-elide-api
description: Guidelines and patterns for implementing or extending JSON:API resources, security checks, and lifecycle hooks using Yahoo Elide in Terrakube API (`io.terrakube.api.rs`), including Liquibase database migrations.
---

# Terrakube Yahoo Elide JSON:API Implementation Guide

Terrakube exposes its core REST API using **Yahoo Elide** (`com.yahoo.elide:elide-spring-boot-starter`), which binds JPA entities directly to standard JSON:API (v1.0) endpoints.

## Package Layout (`io.terrakube.api.rs`)
* `io.terrakube.api.rs` - Contains top-level entity classes and sub-packages per domain model (e.g., `workspace`, `organization`, `job`, `project`, `module`, `vcs`, `ssh`, `template`, `provider`).
* `io.terrakube.api.rs.checks` - Contains custom `@SecurityCheck` implementations extending `OperationCheck<T>`.
* `io.terrakube.api.rs.hooks` - Contains `LifeCycleHook<T>` classes triggered on entity CREATE, UPDATE, or DELETE events.

---

## 1. Creating or Extending a JSON:API Resource Entity

When adding a new resource or modifying an existing entity:

1. **Annotations Required**:
   - `@Include`: Registers class as an Elide JSON:API resource.
   - `@Entity(name = "...")`: Hibernate entity mapping.
   - `@Getter` / `@Setter`: Lombok accessors.
   - `@ReadPermission`, `@CreatePermission`, `@UpdatePermission`, `@DeletePermission`: Defines access rules using registered security check expressions.
   - `@SQLRestriction`: (If applicable) Soft-delete filter (e.g. `@SQLRestriction("deleted = false")`).

2. **Primary Key Mapping**:
   - Always use `UUID` with `@JdbcTypeCode(Types.VARCHAR)` and `@GeneratedValue(strategy = GenerationType.UUID)`:
   ```java
   @Id
   @JdbcTypeCode(Types.VARCHAR)
   @GeneratedValue(strategy = GenerationType.UUID)
   private UUID id;
   ```

3. **Audit Fields**:
   - Extend `GenericAuditFields` for standard auditing fields (`createdDate`, `updatedDate`, `createdBy`, `updatedBy`).

4. **Relationships**:
   - Map relationships using standard JPA annotations (`@OneToMany`, `@ManyToOne`). Elide automatically generates nested routes (`/api/v1/organization/{id}/workspace`) and relationship links.

---

## 2. Implementing Security Checks (`@SecurityCheck`)

Elide security expressions (e.g. `@ReadPermission(expression = "user belongs organization")`) map to custom Spring-managed check components:

1. Place checks in `io.terrakube.api.rs.checks.<domain>`.
2. Extend `com.yahoo.elide.core.security.checks.OperationCheck<T>`.
3. Annotate class with `@SecurityCheck(RULE)` and define `public static final String RULE = "...";`.
4. Inject Spring beans (e.g. `AuthenticatedUser`, `GroupService`) using `@Autowired`.
5. Implement `ok(T entity, RequestScope requestScope, Optional<ChangeSpec> changeSpec)` returning `boolean`.

Example:
```java
@Slf4j
@SecurityCheck(UserBelongsOrganization.RULE)
public class UserBelongsOrganization extends OperationCheck<Organization> {
    public static final String RULE = "user belongs organization";

    @Autowired
    AuthenticatedUser authenticatedUser;

    @Override
    public boolean ok(Organization organization, RequestScope requestScope, Optional<ChangeSpec> optional) {
        if (authenticatedUser.isSuperUser(requestScope.getUser())) return true;
        return isMemberOrganization(requestScope.getUser(), organization);
    }
}
```

> [!CAUTION]
> **No Underscores in Elide Security Check Expressions**:
> Elide's expression tokenizer does not recognize underscore (`_`) characters in permission strings.
> Always use space-separated alphanumeric words (e.g., `@SecurityCheck(TeamViewPolicySet.RULE)` where `RULE = "team view policy set"`, and `@ReadPermission(expression = "team view policy set")`).
> Using `team_view_policy_set` causes `token recognition error at: '_'` during Elide application startup.

> [!NOTE]
> **Collection Routing & Entity Naming**:
> When an entity specifies `@Entity(name = "snake_case_name")`, Yahoo Elide registers the root REST collection endpoint as `/api/v1/snake_case_name`. External clients, HTTP services, and Terraform provider resources must use snake_case URLs (e.g. `/api/v1/policy_set`), not camelCase.

---

## 3. Implementing Lifecycle Hooks (`LifeCycleHook`)

Hooks execute custom side-effects during Elide operations (CREATE, UPDATE, DELETE):

1. Place hook in `io.terrakube.api.rs.hooks.<domain>`.
2. Implement `com.yahoo.elide.core.lifecycle.LifeCycleHook<T>`.
3. Annotate target entity with `@LifeCycleHookBinding`:
   ```java
   @LifeCycleHookBinding(
       operation = LifeCycleHookBinding.Operation.CREATE,
       phase = LifeCycleHookBinding.TransactionPhase.PRECOMMIT,
       hook = WorkspaceManageHook.class
   )
   ```
4. Implement `execute(Operation operation, TransactionPhase phase, T entity, RequestScope scope, Optional<ChangeSpec> changes)` method to handle pre-commit/post-commit logic.

---

## 4. Liquibase Database Migrations Management

Terrakube uses **Liquibase** to manage database migrations across supported RDBMS engines (PostgreSQL, H2, Oracle, MySQL, SQL Server).

### Migration Directory Structure
* Main Master Changelog: `api/src/main/resources/db/changelog/changelog.xml`
* Version & Feature Changelogs: `api/src/main/resources/db/changelog/local/`

### Workflow for Database Schema Changes:
1. **Create New Migration File**:
   - Location: `api/src/main/resources/db/changelog/local/changelog-<version>-<feature>.xml`
   - Example: `changelog-2.32.0-new-entity.xml`

2. **Write ChangeSet**:
   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <databaseChangeLog
           xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
           xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
           xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
               http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.3.xsd">
       <changeSet id="<version>-<feature-description>" author="developer-email@example.com">
           <createTable tableName="new_feature">
               <column name="id" type="varchar(36)">
                   <constraints primaryKey="true"/>
               </column>
               <column name="name" type="varchar(256)">
                   <constraints nullable="false"/>
               </column>
               <column name="created_date" type="datetime"/>
               <column name="updated_date" type="datetime"/>
               <column name="created_by" type="varchar2(128)"/>
               <column name="updated_by" type="varchar2(128)"/>
           </createTable>
       </changeSet>
   </databaseChangeLog>
   ```

3. **Register in `changelog.xml`**:
   - Add the `<include>` entry at the end of `api/src/main/resources/db/changelog/changelog.xml`:
   ```xml
   <include file="/db/changelog/local/changelog-2.32.0-new-entity.xml" />
   ```

---

## 5. End-to-End Checklist for Adding a New Elide Feature

When introducing a new domain model or REST API capability to Terrakube:
1. [ ] **Liquibase**: Create changelog XML in `db/changelog/local/` and include it in `changelog.xml`.
2. [ ] **JPA/Elide Entity**: Create `@Entity` in `io.terrakube.api.rs.<domain>` with `@Include`, Lombok getters/setters, `@JdbcTypeCode(Types.VARCHAR)` + `@GeneratedValue(strategy = GenerationType.UUID)` on `UUID id`, and standard audit fields (`GenericAuditFields`).
3. [ ] **Security Checks**: Define custom `OperationCheck<T>` classes in `io.terrakube.api.rs.checks.<domain>` annotated with `@SecurityCheck(RULE)`.
4. [ ] **Entity Permission Binding**: Annotate the entity class and/or sensitive fields with `@ReadPermission`, `@CreatePermission`, `@UpdatePermission`, `@DeletePermission`.
5. [ ] **Lifecycle Hooks**: If business side-effects (creating resources, notifying webhooks, validating data) are needed, create a `LifeCycleHook<T>` implementation in `io.terrakube.api.rs.hooks.<domain>` and bind it via `@LifeCycleHookBinding`.

---

## 6. Shared Repository Webhooks & Async Reconciliation Architecture

When managing VCS webhooks shared across multiple workspaces (e.g., GitHub, GitLab, and Azure DevOps v2 webhooks):

1. **Elide Lifecycle Hook Binding (`WebhookManageHook` & `WebhookEventManageHook`)**:
   - Synchronous operations (e.g. legacy per-workspace hook deletion) run in `PRECOMMIT`.
   - Asynchronous reconciliation triggers must run in `POSTCOMMIT` via `RepoWebhookSyncScheduler`. `POSTCOMMIT` guarantees database transactions are committed before background Quartz jobs query workspace data.
   - Separate lifecycle hooks MUST be bound to both `Webhook` (`WebhookManageHook`) AND child `WebhookEvent` entities (`WebhookEventManageHook`). Event-only mutations (e.g. toggling event types via Terraform provider or REST clients) must independently trigger post-commit reconciliation.

2. **Quartz Job Coalescing (`RepoWebhookSyncScheduler`)**:
   - Deterministically key Quartz jobs using `SHA-256(normalizedRepositoryUrl)`.
   - Catch both `ObjectAlreadyExistsException` and `JobPersistenceException` (unique constraint violations on the Quartz JDBC job store) to coalesce concurrent workspace/event edits into a single background job run.

3. **Disallow Concurrent Execution (`RepoWebhookSyncJob`)**:
   - Annotate `Job` implementations with `@DisallowConcurrentExecution`.
   - Fetch all workspaces matching the normalized URL with `isMigratedV2 = true`.
   - If workspaces remain: aggregate event types across all workspaces and create/update the single shared VCS webhook.
   - If no workspaces remain: execute `cleanupIfOrphan()` to remove the VCS webhook and DB entity.

4. **Testing Shared Webhook Logic (`RepoWebhookServiceTest`)**:
   - Test DB race-condition recovery when `saveAndFlush` throws `DataIntegrityViolationException`.
   - Validate multi-workspace event aggregation (`PUSH`, `PULL_REQUEST`, `RELEASE`).
   - Validate fanout job generation filtering by branch pattern, file changes, and priority.
   - **Reflection-based binding tests**: Add `WebhookLifeCycleHookBindingTest` and `WebhookEventLifeCycleHookBindingTest` using `getAnnotationsByType(LifeCycleHookBinding.class)` to verify `@LifeCycleHookBinding` registrations directly, as standard unit tests invoking `execute()` bypass Elide annotation bindings.

For code examples and detailed architectural breakdown, see [shared-webhooks.md](references/shared-webhooks.md).

---

## 7. Compilation & JDK Requirements

Terrakube platform modules (`executor`, `api`, `registry`) require **JDK 25** (`<java.version>25</java.version>`), whereas `terraform-spring-boot` requires **JDK 21**.

### Switching JDK via SDKMAN for Compilation:
```bash
# Switch to JDK 25 in current shell context:
sdk use java 25.0.1-librca

# Or execute Maven with an explicit JDK 25 JAVA_HOME:
mvn compile -pl executor
```

---

## 8. Terraform & OpenTofu Binary Caching Architecture

Terrakube Executor caches Terraform and OpenTofu binaries in cloud storage to prevent fresh Kubernetes pods from re-downloading binaries from HashiCorp or GitHub endpoints. Cached binaries are keyed by OS and architecture so mixed-architecture executors sharing one storage backend never restore an incompatible binary.

### 8.1 `TerraformState` Interface Methods
* `saveTerraformBinary(String version, boolean tofu, String os, String arch, File binaryFile)`: Uploads binary executable to storage. Default implementation returns `false`.
* `downloadTerraformBinary(String version, boolean tofu, String os, String arch, File targetFile)`: Downloads cached binary executable from storage and sets executable permissions (`setExecutable(true, true)`). Default implementation returns `false`.

### 8.2 Storage Provider Implementations & Keys
* **AWS S3** (`AwsTerraformStateImpl`): `tfbinary/{product}/{version}/{os}_{arch}/{product}` in configured bucket.
* **Azure Blob** (`AzureTerraformStateImpl`): Container `tfbinary`, blob path `{product}/{version}/{os}_{arch}/{product}`.
* **GCP Storage** (`GcpTerraformStateImpl`): `tfbinary/{product}/{version}/{os}_{arch}/{product}` in configured GCS bucket.
* **Local Storage** (`LocalTerraformStateImpl`): `/.terraform-spring-boot/local/binary/{product}/{version}/{os}_{arch}/{product}` under user home directory.

### 8.3 Executor Orchestration (`TerraformExecutorServiceImpl` & `BinaryCacheRecoveryService`)
Before and after `terraformClient.init()`:
1. **Pre-Init Check (`ensureBinaryCached`)**:
   - Resolves constraint to concrete version via `JobVersionPinner` / `TerraformDownloader` (with leading `v` stripped for OpenTofu).
   - Delegates to `BinaryCacheRecoveryService.resolve(resolvedVersion, tofu)` under a per-`(product, version)` lock.
   - `BinaryCacheRecoveryService` validates any existing local binary (`isValidExecutable`), deletes corrupt/incomplete local binaries or zip archives (`InvalidArchiveReason`), and otherwise attempts `terraformState.downloadTerraformBinary(resolvedVersion, tofu, BinaryArchiveLocator.os(), BinaryArchiveLocator.arch(), binaryFile)`.
   - Re-throws `BinaryCacheRecoveryException` if a corrupt cached archive/binary cannot be cleaned up so the job fails cleanly rather than looping on a broken cache entry.
2. **Post-Init Cache Backup (`saveBinaryToCache`)**:
   - If the binary was not already cached (`!binaryWasAlreadyCached`) and `init` completed successfully, validates `binaryCacheRecoveryService.isValidExecutable(binaryFile)` and uploads it via `terraformState.saveTerraformBinary(resolvedVersion, tofu, BinaryArchiveLocator.os(), BinaryArchiveLocator.arch(), binaryFile)` (cache upload failures are logged as warnings).

---

## 9. Yahoo Elide GraphQL Queries, Pagination & Filtering Guide

Terrakube exposes its primary GraphQL API through Yahoo Elide at `/graphql/api/v1`. Elide adheres to the **GraphQL Relay Connection Specification**, supporting connections (`edges`, `node`, `cursor`, `pageInfo`), built-in security checks, and datastore-level query optimization.

### 9.1 Endpoint & Query Structure

* **Endpoint:** `POST /graphql/api/v1`
* **Headers:**
  * `Authorization: Bearer <token>`
  * `Content-Type: application/json`
* **Envelope:** Standard GraphQL POST payload: `{"query": "...", "variables": { ... }}`

#### Canonical Organization Workspaces Query:
```graphql
query GetWorkspacesPage(
  $orgId: String!
  $first: Int!
  $after: String
  $sort: String
  $filter: String
) {
  organization(ids: [$orgId]) {
    edges {
      node {
        id
        name
        workspace(first: $first, after: $after, sort: $sort, filter: $filter) {
          edges {
            cursor
            node {
              id
              name
              description
              source
              branch
              terraformVersion
              iacType
              lastJobStatus
              lastJobDate
              locked
              workspaceTag {
                edges {
                  node {
                    tagId
                  }
                }
              }
              project {
                edges {
                  node {
                    id
                    name
                  }
                }
              }
            }
          }
          pageInfo {
            startCursor
            endCursor
            hasNextPage
            hasPreviousPage
            totalRecords
          }
        }
      }
    }
  }
}
```

---

### 9.2 Pagination Architecture in Elide

Elide supports two pagination modes on relationship and root collections:

#### Mode 1: Offset Pagination (Default — Recommended for Ant Design UI)
* **Arguments:**
  * `first: Int`: Number of records to return (page size, e.g. `20`).
  * `after: String`: The offset index to skip (e.g. `"0"` for page 1, `"20"` for page 2, `"40"` for page 3).
* **`pageInfo` Structure:**
  * `startCursor`: First record offset in the current page.
  * `endCursor`: Last record offset in the current page.
  * `hasNextPage`: Boolean indicating if subsequent records exist.
  * `hasPreviousPage`: Boolean indicating if preceding records exist.
  * `totalRecords`: Total matching record count across all pages (backed by `SELECT COUNT(...)`).
* **Ant Design `<Pagination />` Integration:**
  Offset pagination maps directly to page numbers:
  ```ts
  const offset = (page - 1) * pageSize;
  const variables = {
    first: pageSize,
    after: String(offset),
  };
  ```
  Users can jump to **any** page (`1`, `2`, `5`, `10`) without requiring sequential forward-cursor traversal.

#### Mode 2: Keyset / Cursor Pagination
* **Enabling on Entity:** Keyset cursor pagination is opt-in via the `@Paginate` annotation:
  ```java
  @Paginate(modes = { PaginationMode.OFFSET, PaginationMode.CURSOR })
  @Entity(name = "workspace")
  public class Workspace extends GenericAuditFields { ... }
  ```
* **How It Works:**
  * Elide generates keyset SQL (`WHERE (sortField, id) > (:cursorValue, :cursorId)`).
  * `after`: Receives the opaque string returned from the previous request's `pageInfo.endCursor`.
  * Best for infinite scroll or large data exports where `OFFSET` performance degrades.

---

### 9.3 RSQL Filtering Syntax (`filter` Argument)

Elide filters collections using **RSQL** (REST Query Language, a superset of FIQL). RSQL operators:
* `==`: Exact equal (case-sensitive)
* `=ini=`: Case-insensitive wildcard match (`*pattern*`)
* `=in=`: In list (`=in=('a','b')`)
* `=out=`: Not in list
* `=isnull=true` / `=isnull=false`: Null checks
* `;`: Logical AND (conjunction)
* `,`: Logical OR (disjunction)

#### Practical Terrakube Filter Examples:

| Use Case | Elide RSQL Syntax |
| :--- | :--- |
| **Status Filter** | `lastJobStatus=='running'` |
| **Never Executed** | `lastJobStatus=='NeverExecuted'` |
| **Project ID** | `project.id=='d9b58bd3-f3fc-4056-a026-1163297e80a8'` |
| **Unassigned Project** | `project=isnull=true` |
| **Tag IDs (Any match)** | `workspaceTag.tagId=in=('tag-1','tag-2')` |
| **Search (Name or Description)** | `(name=ini=*prod*,description=ini=*prod*)` |
| **Combined Conjunction** | `lastJobStatus=='running';project.id=='...';(name=ini=*prod*,description=ini=*prod*)` |

---

### 9.4 Sorting Syntax (`sort` Argument)

* **Syntax:** Comma-separated list of field names.
* Prepend `+` for ascending (default if omitted).
* Prepend `-` for descending.
* Traversal across to-one relationships uses dot notation (`project.name`).

#### Examples:
* Sort by Name (Ascending): `sort: "+name"`
* Sort by Name (Descending): `sort: "-name"`
* Sort by Last Run Date (Descending): `sort: "-lastJobDate"`
* Sort by Status: `sort: "+lastJobStatus"`
* Compound Sort: `sort: "-lastJobDate,+name"`

---

### 9.5 Status Counts via GraphQL Field Aliasing

To populate status badges (e.g., `All (42)`, `Running (2)`, `Completed (5)`) in a single network request without writing custom SQL aggregations, use **GraphQL Field Aliasing** requesting only `pageInfo { totalRecords }`:

```graphql
query GetWorkspaceStatusCounts($orgId: String!) {
  organization(ids: [$orgId]) {
    edges {
      node {
        all: workspace { pageInfo { totalRecords } }
        running: workspace(filter: "lastJobStatus=='running'") { pageInfo { totalRecords } }
        completed: workspace(filter: "lastJobStatus=='completed'") { pageInfo { totalRecords } }
        failed: workspace(filter: "lastJobStatus=='failed'") { pageInfo { totalRecords } }
        waitingApproval: workspace(filter: "lastJobStatus=='waitingApproval'") { pageInfo { totalRecords } }
        neverExecuted: workspace(filter: "lastJobStatus=='NeverExecuted'") { pageInfo { totalRecords } }
      }
    }
  }
}
```

This returns:
```json
{
  "data": {
    "organization": {
      "edges": [{
        "node": {
          "all": { "pageInfo": { "totalRecords": 42 } },
          "running": { "pageInfo": { "totalRecords": 2 } },
          "completed": { "pageInfo": { "totalRecords": 35 } },
          "failed": { "pageInfo": { "totalRecords": 3 } },
          "waitingApproval": { "pageInfo": { "totalRecords": 1 } },
          "neverExecuted": { "pageInfo": { "totalRecords": 1 } }
        }
      }]
    }
  }
}
```


