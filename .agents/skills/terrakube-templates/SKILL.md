---
name: terrakube-templates
description: Architecture, TCL schema, runtime bindings, execution mechanics, security hardening invariants, and recipes for authoring Terrakube workflow templates using Groovy and Bash.
---

# Terrakube Templates (TCL) Authoring & Execution Guide

Terrakube Templates define the execution pipeline of workspace jobs using the **Terrakube Command Language (TCL)**. Templates are written in YAML and orchestrate Terraform operations (`terraformPlan`, `terraformApply`), manual approvals, and custom scripts executed in **Groovy** or **Bash**.

---

## 1. Top-Level TCL Structure & Step Types

Every template consists of a `flow:` list executed sequentially according to the `step` number:

```yaml
flow:
  - type: "customScripts"
    name: "Step Name"
    step: 100
    commands:
      - runtime: "GROOVY"
        priority: 100
        script: |
          println "Hello from Groovy"
```

### Supported Step Types (`FlowType`)

| Step Type | Description | Supports `commands:`? |
|---|---|---|
| `terraformPlan` | Executes `terraform init` and `terraform plan`. | Yes (`before`, `after`, `beforeInit`) |
| `terraformApply` | Executes `terraform apply` using the plan. | Yes (`before`, `after`, `beforeInit`) |
| `terraformDestroy` | Executes `terraform destroy`. | Yes (`before`, `after`, `beforeInit`) |
| `terraformPlanDestroy` | Generates a speculative destroy plan (CLI-driven). | Yes (`before`, `after`, `beforeInit`) |
| `customScripts` | Standalone script step without running Terraform commands. | Yes (runs all listed commands) |
| `approval` | Pauses execution and waits for team approval. | Evaluates `team:` field; rejects run `onReject` |
| `policyEvaluation` | Evaluates OPA policies on the generated plan. | No |
| `disableWorkspace` | Marks workspace as disabled/deleted. | No |
| `scheduleTemplates` | Dynamically triggers sub-templates. | Evaluates `templates:` field |
| `yamlError` | Internal sentinel when TCL YAML parsing fails. | No |

### Flow Step Fields

* `type` (required): One of the `FlowType` values above.
* `name` (optional): Human-readable title displayed in the UI step logs.
* `step` (required): Integer step sequence number (e.g., `100`, `150`, `200`).
* `team` (optional): Team name required for manual approval (used when `type: "approval"`).
* `ignoreError` (optional, boolean): If `true`, a failure in this step will not abort subsequent steps.
* `requireSavedPlan` (optional, boolean): On `terraformApply`, requires the saved plan artifact (`terraformLibrary.tfPlan`) to exist and be non-empty, failing before apply even if `ignoreError: true` and applying without injecting workspace Terraform variables.
* `artifacts` (optional): Artifact glob patterns saved/restored across steps.
* `inputsEnv` / `inputsTerraform` (optional, boolean): Controls whether workspace environment or Terraform variables are injected for the step.
* `importCommands` (optional): Imports reusable command sets (`ImportCommands`).
* `commands` (optional): List of command objects to execute.
* `onFailure` (optional): List of command objects executed if this step fails.
* `onReject` (optional): List of command objects executed if an approval is rejected.

---

## 2. Command Specification

Each command inside `commands:`, `onFailure:`, or `onReject:` has the following structure:

```yaml
commands:
  - runtime: "GROOVY"       # "GROOVY" or "BASH"
    priority: 100           # Integer execution order (ascending)
    before: true            # Run before main step action (for terraformPlan/Apply)
    after: false            # Run after main step action (for terraformPlan/Apply)
    beforeInit: false       # Run before `terraform init` executes
    verbose: true           # If true, prints execution banner and script preview
    script: |
      # Multi-line script content
```

### Timing Flags for Terraform Steps

* **`beforeInit: true`**: Runs *before* `terraform init`. Ideal for generating dynamic backend configurations, `.terraformrc`, or pre-downloading custom tools.
* **`before: true`**: Runs *after* `terraform init` but *before* `terraform plan/apply/destroy`. Ideal for linting (`tflint`), security scanning (`tfsec`), or tagging (`terratag`).
* **`after: true`**: Runs *after* `terraform plan/apply/destroy`. Ideal for parsing plan output, saving cost estimates to job context, or sending webhook notifications.

*(Note: In `type: "customScripts"`, all commands are executed directly in order of `priority`.)*

---

## 3. Injected Environment & Bindings Reference

Terrakube automatically populates the process environment and script bindings for both **Groovy** and **Bash** commands:

| Variable | Type | Description |
|---|---|---|
| `workingDirectory` | String | Absolute path to the workspace working directory where code is cloned. |
| `bashToolsDirectory` | String | Path to `.terrakube/tools` directory (automatically added to `$PATH`). |
| `terrakubeToolsRepository` | String | Path to `.terrakube/toolsRepository` (cloned extensions repository). |
| `terrakubeEnv` | String | Path to `.terrakube_temp_env` file used to persist variables between commands. |
| `terrakubeApi` | String | Base URL of the Terrakube API (e.g. `https://terrakube-api.localhost`). |
| `terrakubeToken` | String | Short-lived scoped JWT bearer token (scoped to `organizationId`, `workspaceId`, `jobId`, `stepId`). |
| `accessToken` | String | VCS OAuth/installation access token when available for the workspace repository. |
| `organizationId` | String | UUID of the organization. |
| `workspaceId` | String | UUID of the workspace. |
| `jobId` | String | Identifier of the current Job. |
| `stepId` | String | Identifier of the current Step. |
| `terraformVersion` | String | Selected Terraform/OpenTofu version for the workspace. |
| `source` | String | VCS repository source URL. |
| `branch` | String | Target Git branch. |
| `vcsType` | String | VCS provider type (e.g. `GITHUB`, `GITLAB`, `PUBLIC`). |
| `terraformOutput` | String | Stdout text captured from `terraform plan` or `terraform apply`. |
| `terraformOutputJson` | String | *(Groovy only)* JSON-formatted output captured from Terraform operations. |
| `terrakubeOutput` | PrintStream | *(Groovy only)* Output stream mapped to the UI execution logs (`System.out`). |

In addition, any workspace environment variables and Terraform variables (`TF_VAR_...`) configured in the workspace are loaded into the environment.

---

## 4. Under-the-Hood Execution Mechanics

### 1. Step Dispatch & Tool Provisioning (`ScriptEngineService`)
When `ExecutorJobImpl` processes a step containing script commands:
1. `ScriptEngineService.execute(...)` is invoked.
2. `createToolsDirectory()` clones the configured extensions repository (`io.terrakube.tools.repository` and `io.terrakube.tools.branch`, populated from `${TerrakubeToolsRepository}`) into `$workingDirectory/.terrakube/toolsRepository`.
3. Commands are inserted into a `TreeMap<Integer, Command>` keyed by `priority` (ascending order; duplicate priorities overwrite earlier commands) and dispatched to either `GroovyEngine` or `BashEngine`.
4. When execution finishes in the `try` block, `cleanToolsDirectory()` purges the cloned extensions directory.

### 2. Process Orchestration (`ProcessLauncher`)
Both engines rely on `ProcessLauncher` to execute commands as separate OS processes:
* Spawns OS processes using `java.lang.ProcessBuilder`, applying Linux PID namespace isolation (`unshare --map-root-user --user --pid --fork --mount-proc`) when available.
* Clears ambient host environment variables via `processLauncher.clearEnvironment()`.
* Uses `Executors.newWorkStealingPool()` to launch two asynchronous consumer threads that continuously drain `stdout` and `stderr`. This prevents OS pipe buffer deadlocks when scripts produce large volumes of output.
* Bridges output back to Terrakube logs via `Consumer<String>` listeners.
* Waits for completion via `CompletableFuture<Integer>` and checks process exit codes.

### 3. Groovy Execution Flow (`GroovyEngine` & `GroovyProcessRunner`)
1. **Script Serialization**: Writes the command script to `$workingDirectory/userScript.groovy`.
2. **Process Spawn**: Launches a separate, dedicated child JVM (using `org.apache.groovy:groovy-all` `6.0.0`, supporting both standard classpath and fat-JAR `PropertiesLauncher` layouts) with `$JAVA_HOME/bin/java -cp <classpath> GroovyProcessRunner <scriptPath> <toolsRepoPath>`.
3. **Environment Sanitization**: Calls `processLauncher.clearEnvironment()` to strip ambient host secrets. Only whitelisted safe system variables (`SAFE_SYSTEM_ENV`) plus explicit job parameters are injected.
4. **Classpath Scanning (`GroovyProcessRunner`)**:
   * Scans `$workingDirectory/.terrakube/toolsRepository` for all `.groovy` files.
   * Registers their parent directories as class roots in `GroovyScriptEngine`.
   * Automatically allows scripts to import extension classes (e.g. `import Context`).
5. **Binding Population**: `GroovyProcessRunner` populates `groovy.lang.Binding` from the sanitized process environment and binds `terrakubeOutput = System.out`.
6. **Execution**: `engine.run(scriptFile.getName(), sharedData)` executes the script out-of-process.

### 4. Bash Execution Flow (`BashEngine`)
1. Writes the script to `$workingDirectory/userScript.sh` (marking `.sh` files under `toolsRepository` and `workingDirectory` executable via `loadingBashTools`).
2. Calls `processLauncher.clearEnvironment()` and whitelists `SAFE_SYSTEM_ENV` + scoped job context variables.
3. Spawns `bash` (`"bash", bashScript.getAbsolutePath()`) with working directory set to `$workingDirectory`.
4. Automatically prepends `$bashToolsDirectory` (`.terrakube/tools`) to `$PATH`.
5. Sources and injects variables defined in `$workingDirectory/.terrakube_temp_env`.

---

## 5. Security Best Practices & Invariants

Terrakube enforces strict architectural invariants so untrusted templates cannot escalate privileges or leak platform secrets:

### Invariant 1: Out-of-Process Isolation Over In-Process Sandboxing
* **The Vulnerability**: Historically, script engines relied on in-process compilation customizers (like Groovy's `SecureASTCustomizer`). In modern Java (Java 21+), `SecurityManager` has been permanently removed. AST customizers can restrict explicit syntax (e.g. `System.exit()`), but attackers easily bypass them via dynamic reflection:
  ```groovy
  // Bypass in-process AST sandboxing
  def c = Class.forName("java.lang.System")
  def m = c.getMethod("getenv", [String.class] as Class[])
  def secret = m.invoke(null, ["InternalSecret"] as Object[])
  ```
* **Best Practice**: **NEVER run untrusted scripts in the same JVM as the Terrakube application.** Always execute scripts out-of-process via child OS processes or ephemeral containers.

### Invariant 2: Whitelist-Only Process Environment Sanitization
* **The Trap**: Blacklisting sensitive keys (e.g. removing `InternalSecret`) is fragile. New sensitive variables added to the parent application (database passwords, Redis tokens, cloud keys) are automatically leaked to child processes.
* **Best Practice**: Child process environment tables MUST be cleared (`processLauncher.clearEnvironment()`). Only pass:
  1. An explicit whitelist of safe system variables: `PATH`, `HOME`, `USER`, `SHELL`, `TMPDIR`, `LANG`, `LC_ALL`, `TERM`.
  2. The explicitly generated job context variables (`terrakubeApi`, `terrakubeToken`, `jobId`, etc.).

### Invariant 3: Short-Lived, Context-Scoped Access Tokens
* **The Trap**: Passing administrative internal tokens to scripts gives template authors platform superuser access.
* **Best Practice**:
  * Tokens minted for templates MUST be strictly scoped:
    * `organizationId`: Target organization UUID.
    * `workspaceId`: Target workspace UUID.
    * `jobId`: Current job identifier.
    * `stepId`: Current step identifier.
    * `jti`: Unique identifier derived from `${jobId}-${stepId}`.
  * Tokens must have short expirations (e.g., 5 to 10 minutes).
  * Backend API endpoints MUST validate that incoming tokens cannot perform actions outside their scoped workspace (fail closed on missing or mismatched workspace IDs).

### Invariant 4: Zero Host Bean Injection
* **Best Practice**: **NEVER expose Spring service beans, DAOs, or client SDK instances (`TerrakubeClient`) directly inside script bindings.**
* Scripts must interact with the platform exclusively via external HTTP REST APIs (`terrakubeApi`) authenticated with their short-lived `terrakubeToken`.

### Invariant 5: Guaranteed Filesystem & Workspace Teardown
* Scripts and temporary tools must be purged immediately after execution:
  * Ephemeral scripts (`userScript.groovy`, `userScript.sh`) and `.terrakube_temp_env` must reside only within the isolated working directory.
  * The tools repository (`.terrakube/toolsRepository`) is purged via `cleanToolsDirectory()` when `ScriptEngineService.execute()` finishes (ensure cleanup runs reliably even across error paths).
  * Temporary credential files (e.g. `~/.terraformrc`) must be wiped after each step.

---

## 6. Tested Template Recipes

### Recipe 1: Standalone Security & Environment Diagnostics (`customScripts`)
Runs immediately without requiring Terraform repositories or locks:

```yaml
flow:
  - type: "customScripts"
    name: "Environment & Security Check"
    step: 100
    commands:
      - runtime: "GROOVY"
        priority: 100
        script: |
          println "=== Running Groovy Diagnostics ==="
          assert System.getenv("InternalSecret") == null : "Host secret leaked!"
          println "Job ID: ${jobId}"
          println "Workspace ID: ${workspaceId}"
          println "API Endpoint: ${terrakubeApi}"
          
          import Context
          def ctx = new Context(terrakubeApi, terrakubeToken, jobId, workingDirectory)
          ctx.saveProperty("diagnostic_run", "SUCCESS")
          println "Diagnostic check completed successfully."
      - runtime: "BASH"
        priority: 200
        script: |
          echo "=== Running Bash Diagnostics ==="
          echo "Current directory: $(pwd)"
          echo "Terraform version: $terraformVersion"
          echo "Tools in PATH: $PATH"
```

### Recipe 2: Cost Estimation Flow (Infracost + Context)
Demonstrates pre-downloading a tool in Groovy, running analysis in Bash, and storing results in Context:

```yaml
flow:
  - type: "terraformPlan"
    name: "Plan with Infracost Breakdown"
    step: 100
    commands:
      - runtime: "GROOVY"
        priority: 100
        before: true
        script: |
          import Infracost
          String credentials = "version: \"0.1\"\napi_key: ${INFRACOST_API_KEY}\npricing_api_endpoint: https://pricing.api.infracost.io"
          new Infracost().loadTool("$workingDirectory", "$bashToolsDirectory", "0.10.12", credentials)
          println "Infracost CLI downloaded and installed to $bashToolsDirectory"
      - runtime: "BASH"
        priority: 200
        after: true
        script: |
          terraform show -json terraformLibrary.tfPlan > plan.json
          infracost breakdown --path plan.json --format json --out-file infracost.json
          echo "Infracost breakdown generated."
      - runtime: "GROOVY"
        priority: 300
        after: true
        script: |
          import Context
          new Context(terrakubeApi, terrakubeToken, jobId, workingDirectory).saveFile("infracost", "infracost.json")
          println "Infracost report saved to Job Context."
  - type: "approval"
    name: "Cost Approval"
    step: 150
    team: "DevOps"
  - type: "terraformApply"
    name: "Apply Infrastructure"
    step: 200
```

### Recipe 3: Dynamic Terraform Configuration (`beforeInit: true`)
Generates backend configuration before `terraform init` executes:

```yaml
flow:
  - type: "terraformPlan"
    name: "Plan with Dynamic Backend"
    step: 100
    commands:
      - runtime: "BASH"
        priority: 100
        beforeInit: true
        script: |
          cat << EOF > backend_override.tf
          terraform {
            backend "local" {}
          }
          EOF
          echo "Generated local backend configuration."
```

### Recipe 4: Failure Notification Hook (`onFailure`)
Executes alerting commands when a step fails:

```yaml
flow:
  - type: "terraformApply"
    name: "Apply"
    step: 100
    onFailure:
      - runtime: "BASH"
        priority: 100
        script: |
          echo "Apply failed for Job $jobId on workspace $workspaceId"
          if [ -n "$SLACK_WEBHOOK_URL" ]; then
            curl -X POST -H 'Content-type: application/json' \
              --data "{\"text\":\"❌ Apply failed for Job $jobId in workspace $workspaceId\"}" \
              "$SLACK_WEBHOOK_URL"
          fi
```
