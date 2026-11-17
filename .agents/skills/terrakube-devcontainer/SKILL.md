---
name: terrakube-devcontainer
description: Connecting to PostgreSQL, inspecting Redis and MinIO, and debugging database and runtime data issues for terrakube.localhost / terrakube-api.localhost in the local devcontainer environment.
---

# Terrakube Devcontainer & Local Database Debugging Guide

This skill documents how to connect to the PostgreSQL database, inspect service state, and troubleshoot data issues in Terrakube (UI: `https://terrakube.localhost`, API: `https://terrakube-api.localhost`) within the local Docker Compose / Devcontainer environment (`.devcontainer`).

---

## 1. Local Environment Architecture & Network Topology

The local development stack runs in Docker under the `terrakube-network` bridge network. Standard browsers and operating systems automatically resolve `*.localhost` to the loopback address (`127.0.0.1`), eliminating the need for custom `/etc/hosts` or DNS mappings.

### Component Endpoints & Network Topology

| Component / Service | Local URL / In-Network Host | Host Port | Credentials / Notes |
| :--- | :--- | :--- | :--- |
| **Terrakube Web UI** | `https://terrakube.localhost` | `80`, `443` | Sign in: `admin@example.com` / `admin` |
| **Terrakube API** | `https://terrakube-api.localhost` | `80`, `443` | JSON:API / GraphQL endpoints (`terrakube-api-https:8080`) |
| **Terrakube Registry** | `https://terrakube-registry.localhost` | `80`, `443` | Module & Provider registry (`terrakube-registry-https:8075`) |
| **Dex OIDC Issuer** | `https://terrakube-dex.localhost/dex` | `80`, `443` | OIDC IdP for local auth & CLI login (`terrakube-dex-https:5556`) |
| **Terrakube Executor** | `http://localhost:8090/api/v1/terraform-rs` (via `AzBuilderExecutorUrl`) | Internal (`terrakube-dev`) | Runs inside `terrakube-dev` with API/Registry; Traefik router labels are commented out in `docker-compose.yml` |
| **`postgresql-service`** | `postgresql-service:5432` | `5432` | User: `terrakube`, DB: `terrakubedb`, Pass: `terrakubepassword` |
| **`redis-service`** (Valkey) | `terrakube-redis:6379` | Internal | Auth: `password123456` |
| **`minio`** (Silo) | `terrakube-minio:9000` (API) / `localhost:9001` | `9000`, `9001` | User: `minioadmin`, Pass: `minioadmin`, Bucket: `sample` |
| **`traefik`** | `terrakube-traefik` | `80`, `443` | Reverse proxy terminating TLS via local `mkcert` certificates |
| **`terrakube-dev`** | `terrakube-dev` | — | Devcontainer workspace (`/workspaces/terrakube`, Java 25 `25.0.1-librca`, Node 24) |

> [!NOTE]
> `scripts/setupDevelopmentEnvironment.sh` defaults to `TerraformStateType=HttpTerraformStateImpl` + `TerraformStateHttpBackend=true`, whereas `.devcontainer/devcontainer.json` `postCreateCommand` passes `-s MINIO` (`AwsTerraformStateImpl` + `TerraformStateHttpBackend=false`).

---

## 2. PostgreSQL Connection Methods

### Method A: Direct Execution via Docker (Fastest & Zero Setup)
Run interactive `psql` or single-command queries directly on the PostgreSQL container:

**Interactive Shell:**
```bash
docker exec -it postgresql-service psql -U terrakube -d terrakubedb
```

**One-liner Query:**
```bash
docker exec postgresql-service psql -U terrakube -d terrakubedb -c "SELECT id, status, comments, created_date FROM job ORDER BY id DESC LIMIT 5;"
```

---

### Method B: From the Host Machine (via Exposed Port 5432)
The PostgreSQL port is mapped to `0.0.0.0:5432` on the host:

**Using `psql`:**
```bash
PGPASSWORD=terrakubepassword psql -h localhost -p 5432 -U terrakube -d terrakubedb
```

**Connection String (for DBeaver, pgAdmin, IntelliJ, VS Code extensions):**
```
postgresql://terrakube:terrakubepassword@localhost:5432/terrakubedb
```

---

### Method C: From Inside the `terrakube-dev` Devcontainer
Inside the `terrakube-dev` container, the host is `postgresql-service`:

1. **Install `psql` client (if not already installed):**
   ```bash
   sudo apt-get update && sudo apt-get install -y postgresql-client
   ```
2. **Connect via container DNS:**
   ```bash
   PGPASSWORD=terrakubepassword psql -h postgresql-service -p 5432 -U terrakube -d terrakubedb
   ```

---

## 3. Common SQL Diagnostic Queries for `terrakube-api`

### 1. Investigating Stuck or Pending Jobs
Check recent jobs, flow types, and status:
```sql
SELECT id, status, comments, plan_changes, created_by, created_date, updated_date
FROM job
ORDER BY id DESC
LIMIT 10;
```

Check why a job is blocked (inspect active steps):
```sql
SELECT id, step_number, status, name, delivery_state
FROM step
WHERE job_id = <JOB_ID>
ORDER BY step_number ASC;
```

### 2. Workspace Status & Execution Locks
Check if a workspace is locked or what its latest job status is:
```sql
SELECT id, name, last_job_status, locked, deleted
FROM workspace
WHERE name = '<WORKSPACE_NAME>';
```

Unlock a stuck workspace:
```sql
UPDATE workspace SET locked = false WHERE name = '<WORKSPACE_NAME>';
```

### 3. Manual Job Remediation (Cancelling Stalled / Stuck Runs)
If a pending run is blocking the FIFO queue or workspace:
```sql
-- Cancel the job
UPDATE job SET status = 'cancelled' WHERE id = <JOB_ID>;

-- Cancel any lingering steps
UPDATE step SET status = 'cancelled' WHERE job_id = <JOB_ID> AND status IN ('pending', 'running', 'queue');
```

### 4. Policy Evaluations & Violations
Check policy scan results for a job:
```sql
SELECT id, job_id, step_id, status, passed_rules, warning_rules, soft_mandatory_violations, hard_mandatory_violations, storage_uri, created_date
FROM policy_evaluation
ORDER BY created_date DESC
LIMIT 5;
```

Check policy overrides:
```sql
SELECT id, evaluation_id, overridden_by, overridden_at, justification, created_date
FROM policy_override
ORDER BY created_date DESC;
```

### 5. Outbox Notifications (Webhook / Slack Deliveries)
Inspect pending or failed notifications:
```sql
SELECT id, job_id, configuration_id, status, job_status, attempt_count, last_error, created_date
FROM notification_outbox
ORDER BY created_date DESC
LIMIT 10;
```

---

## 4. Resetting or Refreshing Database State

To completely reset the local database (wipes all tables and allows Liquibase to re-run from scratch):
```bash
docker compose -f .devcontainer/docker-compose.yml stop postgresql-service
docker compose -f .devcontainer/docker-compose.yml rm -f -v postgresql-service
docker volume rm devcontainer_postgresql_data
docker compose -f .devcontainer/docker-compose.yml up -d postgresql-service
```

---

## 5. Testing Custom `terraform-spring-boot` Library in Devcontainer

To test modifications to the `terraform-spring-boot` library live against the local executor:
1. Build and install into the container's `.m2` repository with `-Drevision=<version>` matching `executor/pom.xml`.
2. Expand `${revision}` in the installed starter and autoconfigure `.pom` files.
3. Sync artifacts to `/home/vscode/.m2/repository` with `chown -R vscode:vscode`.
4. Run `scripts/setupDevelopmentEnvironment.sh` to update `.envExecutor` with any new flags.
5. Launch **"Run Executor"** via VS Code debugger (`F5`).

For full command sequences, troubleshooting, and gotchas, consult:
- See `terrakube-io/terraform-spring-boot` for upgrading and testing `terraform-spring-boot-starter` snapshots locally.

