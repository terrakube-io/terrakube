# Per-job Terraform variables

Job creation accepts a `variables` map of raw `TF_VAR_*` values for that run only. A job variable replaces a workspace or template variable of the same name. Variable files and `-var` arguments keep their normal Terraform precedence.

* Limits: valid Terraform variable names, at most 100, 64 KiB of JSON.
* Values are encrypted at rest, cannot be read or updated through the API, and are decrypted only when a step is dispatched. The names are readable as `variableNames` on the job, and the run page lists them.
* Setting them needs the right to manage the workspace (organization team, workspace access or project access), the same as editing the workspace variables. A team that can only plan can still create jobs, but a request that carries `variables` is refused with a 403.
* The plan file and `plan.json` contain input values, as they do for workspace variables. Mark secret inputs `sensitive = true`.
* Upgrading: finish the rollout before clients send `variables`. An older API pod would run the job without them. `GET /execution/v1/capabilities` reports `jobVariables: true`.
