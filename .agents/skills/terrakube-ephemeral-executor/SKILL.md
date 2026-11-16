---
name: terrakube-ephemeral-executor
description: Comprehensive architecture, configuration options, and execution lifecycle guide for the Terrakube Ephemeral Executor (Kubernetes Jobs).
---

# Terrakube Ephemeral Executor Guide

The Ephemeral Executor runs each job/step in an isolated, on-demand Kubernetes `batch/v1` `Job` rather than dispatching tasks to a long-running persistent executor service over HTTP.

---

## 1. Dispatch & Activation

### Activation Condition
In `ExecutorService.java`, Terrakube checks if `TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR` is present in the job environment and not equal to `"0"` (for example, `"1"`):
```
TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR=1
```
- **If Active (present and `!= "0"`)**: Dispatched to `EphemeralExecutorService.send(job, executorContext)`.
- **If Absent or `"0"`**: Dispatched to `PersistentExecutorService.send(job, executorContext)` via HTTP REST.

### Helm Chart Configuration (Cluster-wide)
To enable the ephemeral executor via Helm:
```yaml
api:
  serviceAccountName: "terrakube-api-service-account"
  ephemeralExecution:
    enabled: true
```

### Required Kubernetes RBAC
The `terrakube-api` service account requires permissions to manage `batch/v1` `jobs` in the target namespace:
```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: terrakube-api-role
  namespace: terrakube
rules:
  - apiGroups: ["batch"]
    resources: ["jobs"]
    verbs: ["get", "list", "watch", "create", "update", "patch", "delete"]
```

---

## 2. Ephemeral Job Lifecycle & Delivery

1. **Job Manifest Generation**:
   - `EphemeralExecutorService` constructs a `batch/v1` `Job` named `job-<jobId>-<timestamp>`.
   - Pod restart policy is set to `Never`, `ttlSecondsAfterFinished` defaults to `30`, and `terminationGracePeriodSeconds` defaults to `60` (with configurable `activeDeadlineSeconds` and `backoffLimit`).
2. **Payload Delivery**:
   - The `ExecutorContext` is serialized to JSON, **encrypted via `EncryptionService.encrypt(jobJson)`**, Base64-encoded, and passed as environment variable `EphemeralJobData`.
   - `EphemeralFlagBatch="true"` is passed to signal batch execution mode.
3. **Server-Side Apply**:
   - Manifest is submitted via Fabric8 `kubernetesClient.batch().v1().jobs().inNamespace(...).resource(k8sJob).serverSideApply()`.
4. **Error Classification & Retries**:
   - `isRetryable(KubernetesClientException)` classifies transient issues (unreachable apiserver, HTTP 429 rate limit, 5xx server errors, HTTP 403 quota exceeded) into `ExecutorUnavailableException` for automatic retry.
   - Genuine misconfigurations or permanent RBAC rejections throw `ExecutionException` to fail immediately.
5. **Execution inside Container**:
   - On container startup, `BatchModeServiceImpl` listens for `ContextRefreshedEvent`, Base64-decodes and decrypts `EphemeralJobData`, deserializes into `TerraformJob`, executes the job steps, and terminates.

---

## 3. Configuration & Options Reference

### Spring Boot Properties (`application.properties`)
| Property | Environment Variable | Default | Description |
| :--- | :--- | :--- | :--- |
| `io.terrakube.executor.ephemeral.namespace` | `ExecutorEphemeralNamespace` | `terrakube` | Target namespace for ephemeral jobs |
| `io.terrakube.executor.ephemeral.image` | `ExecutorEphemeralImage` | `azbuilder/executor:2.34.0` | Executor container image |
| `io.terrakube.executor.ephemeral.secret` | `ExecutorEphemeralSecret` | `terrakube-executor-secrets` | Comma-separated Secret names mounted via `envFrom` |
| `io.terrakube.executor.ephemeral.emptyDirSecret` | `ExecutorEphemeralEmptyDirSecret` | `true` (in `application.properties`) | Uses an `emptyDir` secret copy init container for secure shredding |
| `io.terrakube.executor.ephemeral.initSecretImage` | `ExecutorEphemeralInitSecretImage` | `busybox:1.37.0` | Init container image used when `emptyDirSecret=true` |
| `io.terrakube.executor.ephemeral.activeDeadlineSeconds` | `ExecutorEphemeralActiveDeadlineSeconds` | Unset | Optional Kubernetes `Job.spec.activeDeadlineSeconds` |
| `io.terrakube.executor.ephemeral.backoffLimit` | `ExecutorEphemeralBackoffLimit` | Unset (K8s default `6`) | Optional Kubernetes `Job.spec.backoffLimit` |
| `io.terrakube.executor.ephemeral.ttlSecondsAfterFinished` | `ExecutorEphemeralTtlSecondsAfterFinished` | `30` | Seconds before Kubernetes deletes finished Jobs |
| `io.terrakube.executor.ephemeral.terminationGracePeriodSeconds` | `ExecutorEphemeralTerminationGracePeriodSeconds` | `60` | Pod `terminationGracePeriodSeconds` |
| `io.terrakube.executor.ephemeral.nodeSelector.*` | `-Dio.terrakube.executor.ephemeral.nodeSelector.<key>=<value>` | None | Default node selector key-value pairs |

---

### Workspace / Per-Job Environment Variables
These variables can be set in the workspace (or global variables) with category `ENV`:

#### 1. Resource Requests & Limits
| Variable | Format / Example | Target Resource |
| :--- | :--- | :--- |
| `EPHEMERAL_CPU_REQUEST` | `100m`, `1` | `resources.requests.cpu` |
| `EPHEMERAL_CPU_LIMIT` | `200m`, `2` | `resources.limits.cpu` |
| `EPHEMERAL_MEMORY_REQUEST` | `50Mi`, `1Gi` | `resources.requests.memory` |
| `EPHEMERAL_MEMORY_LIMIT` | `100Mi`, `2Gi` | `resources.limits.memory` |
| `EPHEMERAL_STORAGE_REQUEST` | `2Gi`, `500Mi` | `resources.requests.ephemeral-storage` |
| `EPHEMERAL_STORAGE_LIMIT` | `4Gi`, `1Gi` | `resources.limits.ephemeral-storage` |

#### 2. Node Scheduling & Placement
| Variable | Format / Example | Description |
| :--- | :--- | :--- |
| `EPHEMERAL_CONFIG_NODE_SELECTOR_TAGS` | `key1=val1;key2=val2` | Overrides default node selectors |
| `EPHEMERAL_CONFIG_TOLERATIONS` | `key:operator:effect` or `key=val:operator:effect` | Semicolon-separated list of pod tolerations |

#### 3. Security & Service Accounts
| Variable | Format / Example | Description |
| :--- | :--- | :--- |
| `EPHEMERAL_CONFIG_SERVICE_ACCOUNT` | `my-custom-sa` | Overrides default pod service account |
| `EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT` | `fsGroup=1000;runAsNonRoot=true;runAsUser=1000` | Custom PodSecurityContext |
| `EPHEMERAL_CONFIG_SECURITY_CONTEXT` | `allowPrivilegeEscalation=false` | Custom container SecurityContext |

#### 4. Metadata & Annotations
| Variable | Format / Example | Description |
| :--- | :--- | :--- |
| `EPHEMERAL_CONFIG_ANNOTATIONS` | `key1=val1;key2=val2` | Annotations added to Job metadata |
| `EPHEMERAL_CONFIG_POD_ANNOTATIONS` | `vault.hashicorp.com/agent-inject=true` | Annotations added to Pod template (e.g. Vault Agent, Workload Identity) |
| `EPHEMERAL_CONFIG_LABELS` | `team=infra;env=prod` | Custom labels added to Job and Pod metadata |

#### 5. Storage, ConfigMaps & Secrets
| Variable | Format / Example | Description |
| :--- | :--- | :--- |
| `EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP` | `cm-one,cm-two` | Comma-separated ConfigMap names mounted as `envFrom` |
| `EPHEMERAL_CONFIG_MAP_NAME` | `my-config-map` | ConfigMap mounted as a volume |
| `EPHEMERAL_CONFIG_MAP_MOUNT_PATH` | `/custom/path` (Default: `/data`) | Mount path for `EPHEMERAL_CONFIG_MAP_NAME` |
| `TF_PLUGIN_CACHE_DIR` | `/opt/terrakube/cache` | If set, attaches PVC to cache Terraform plugins |
| `PVC_CLAIM_NAME` | `terrakube-plugin-pvc` (Default) | PVC claim name for plugin cache volume |

#### 6. Custom Container Environment Variables
| Variable | Format / Example | Description |
| :--- | :--- | :--- |
| `EPHEMERAL_JOB_ENV_VARS` | `FOO=bar;BAZ=quux` | Injects additional environment variables directly into the executor container |

---

## 4. CLI-Driven Workspaces & Custom CA Configuration

### 4.1 Creating a CLI-Driven Workspace via API
A CLI-driven workspace in Terrakube uses `branch: "remote-content"` and `source: "empty"`.

**Endpoint**: `POST /api/v1/organization/{organizationId}/workspace`
```json
{
  "data": {
    "type": "workspace",
    "attributes": {
      "name": "cli-ephemeral-test",
      "description": "CLI-driven workspace with ephemeral execution",
      "executionMode": "remote",
      "iacType": "terraform",
      "terraformVersion": "1.5.3",
      "branch": "remote-content",
      "source": "empty"
    }
  }
}
```

### 4.2 Custom CA Root Certificate Mounting
To enable custom CA certificates in the Ephemeral Executor without modifying the container image:
1. **Create ConfigMap in target namespace** (e.g. `my-ca-map` in `terrakube`):
   ```yaml
   apiVersion: v1
   kind: ConfigMap
   metadata:
     name: my-ca-map
     namespace: terrakube
   data:
     type: ca-certificates
     custom-ca.pem: |
       -----BEGIN CERTIFICATE-----
       ...
       -----END CERTIFICATE-----
   ```
2. **Configure Spring Cloud Bindings** via workspace variables.

### 4.3 Essential Ephemeral Workspace Environment Variables
Attach the following variables to the workspace (`POST /api/v1/organization/{orgId}/workspace/{wsId}/variable`) with `category: "ENV"` and `sensitive: false`:

| Key | Value | Purpose |
| :--- | :--- | :--- |
| `TERRAKUBE_ENABLE_EPHEMERAL_EXECUTOR` | `1` | Enables dispatch to Ephemeral Kubernetes Jobs |
| `EPHEMERAL_CONFIG_MAP_NAME` | `my-ca-map` | ConfigMap volume containing custom CA certificate |
| `EPHEMERAL_CONFIG_MAP_MOUNT_PATH` | `/mnt/platform/bindings/ca-certificates` | Standard Spring Cloud Bindings certificate directory |
| `EPHEMERAL_JOB_ENV_VARS` | `SERVICE_BINDING_ROOT=/mnt/platform/bindings;HOME=/tmp` | Enables Spring Cloud Bindings and sets writable `HOME` for non-root user |
| `EPHEMERAL_CONFIG_ENVFROM_CONFIG_MAP` | `terrakube-executor-config` | Injects cluster connection and storage backend properties |
| `EPHEMERAL_CONFIG_POD_SECURITY_CONTEXT` | `runAsUser=1000` | Aligns pod execution UID with the `copy-executor-secrets` init container |

---

## 5. End-to-End CLI Testing & Verification Recipe

### Step 1: Acquire Dex Authentication Token
Dex authenticates against OpenLDAP (default `admin@example.com` / `admin`):
```python
import requests, re, urllib.parse, json, os

API_URL = os.environ.get("TERRAKUBE_API_URL", "https://terrakube-api.localhost")
UI_URL = os.environ.get("TERRAKUBE_UI_URL", "https://terrakube.localhost")
API_HOST = urllib.parse.urlparse(API_URL).hostname

s = requests.Session()
s.verify = os.environ.get("SSL_CERT_FILE", True)

# 1. Initiate auth code flow
r1 = s.get(f"{API_URL}/dex/auth?client_id=example-app&redirect_uri={UI_URL}&response_type=code&scope=openid+email+profile+groups&state=123")
action = re.search(r'action="([^"]+)"', r1.text).group(1).replace('&amp;', '&')
login_url = urllib.parse.urljoin(API_URL, action)

# 2. Submit credentials
r2 = s.post(login_url, data={"login": "admin@example.com", "password": "admin"}, allow_redirects=False)
loc = r2.headers.get("Location")
while "code=" not in loc:
    r2 = s.get(urllib.parse.urljoin(API_URL, loc), allow_redirects=False)
    loc = r2.headers.get("Location")
code = urllib.parse.parse_qs(urllib.parse.urlparse(loc).query)["code"][0]

# 3. Exchange code for JWT id_token
r3 = s.post(f"{API_URL}/dex/token", data={
    "grant_type": "authorization_code",
    "client_id": "example-app",
    "code": code,
    "redirect_uri": UI_URL
})
id_token = r3.json()["id_token"]

# 4. Save to ~/.terraform.d/credentials.tfrc.json
os.makedirs(os.path.expanduser("~/.terraform.d"), exist_ok=True)
with open(os.path.expanduser("~/.terraform.d/credentials.tfrc.json"), "w") as f:
    json.dump({"credentials": {API_HOST: {"token": id_token}}}, f, indent=2)
```

### Step 2: Prepare Local Terraform Configuration
Create a test directory with `main.tf`:
```hcl
terraform {
  cloud {
    hostname     = "terrakube-api.localhost" # Or your cluster's API hostname
    organization = "simple"

    workspaces {
      name = "cli-ephemeral-test"
    }
  }
}

resource "terraform_data" "greeting" {
  input = "Hello from Terrakube Ephemeral Executor with Custom CA!"
}

output "greeting_output" {
  value = terraform_data.greeting.output
}
```

### Step 3: Run Terraform Init & Apply
```bash
export SSL_CERT_FILE=/path/to/rootCA.pem
terraform init
terraform apply -auto-approve
```

### Step 4: Verification Checklist
1. **Init Container**: `kubectl logs <job-pod> -c copy-executor-secrets` exited `0` (directory `chmod 2770 /config-rw`, secret files `chmod 0660`).
2. **Spring Cloud Bindings**: `kubectl logs <job-pod> -c executor` reports:
   `Added 1 additional CA certificate(s) to system truststore`
   `Spring Cloud Bindings Enabled`
3. **Secret Shredding**: `ExecutorSecretEnvironmentPostProcessor` consumed `/etc/terrakube/secrets/*` and shredded files from memory `emptyDir`.
4. **Execution Completion**: Pod completes with exit `0`, logs show `Terraform apply Executed Successfully: true`, and the API job status is `completed`.

---

## 6. Development & Testing Conventions

- **Unit Testing**: Test all ephemeral executor logic in `EphemeralExecutorServiceTest.java` and configuration mapping in `EphemeralConfigurationTest.java`.
- **JDK Requirement**: Use Java 25 when running Maven tests:
  ```bash
  mvn test -Dtest="EphemeralExecutorServiceTest,EphemeralConfigurationTest" -pl api
  ```
