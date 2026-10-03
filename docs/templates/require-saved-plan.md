# Require a saved plan before applying

Set `requireSavedPlan: true` on a `terraformApply` template step to require the
saved plan from that job. For example:

```yaml
flow:
  - type: terraformPlan
    step: 100
  - type: approval
    step: 200
    team: TERRAKUBE_ADMIN
  - type: terraformApply
    step: 300
    requireSavedPlan: true
```

The executor must successfully download `terraformLibrary.tfPlan`, and the file
must exist, be a regular file, and contain data. If any check fails, the step
fails before invoking apply, even if `ignoreError` is enabled. Run a new job to
produce and review another plan before applying.

When a plan is available, the executor applies it without passing the current
Terraform input variables. Terraform or OpenTofu validates the plan, including
whether it is stale. A rejected plan is not retried as a fresh apply.

The default is `false`. Omitting the setting or explicitly setting it to `false`
preserves existing behavior: if the plan cannot be downloaded, the executor
passes the workspace Terraform variables to the client, which can run a fresh
apply when that variables map is nonempty. Existing templates are unchanged.

The setting applies only to `terraformApply`; it does not create an approval
step. Both the API and the executor must support the setting, including any
ephemeral executor images, before a template can rely on it.
