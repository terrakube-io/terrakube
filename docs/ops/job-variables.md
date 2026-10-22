# Per-job Terraform variables

Job creation accepts a `variables` map so a client (for example a CI pipeline) can supply Terraform
inputs for one run without changing the workspace variables.

```json
{"data": {"type": "job", "attributes": {"variables": {"image_tag": "v1.2.3"}}, "relationships": {"workspace": {...}}}}
```

## How the values are handled

* Names must be valid Terraform variable names, at most 100 variables, and at most 64 KiB once encoded as
  JSON. Values are raw `TF_VAR_*` text, so multiline strings and HCL lists or maps work as they do in the
  environment.
* Values are encrypted before they are stored (`job.encrypted_variables`). They cannot be read or updated
  through the API, and they are decrypted only when a step is sent to the executor. A value that cannot be
  decrypted (for example after the encryption secret was changed) fails that run, and the job stays
  readable.
* The names, never the values, are readable as `variableNames` on the job, so a run page or an audit can
  show which inputs a run was given.
* Each step of the job receives them, including an apply of a saved plan. A job variable replaces a
  workspace or template variable of the same name for that run only. Variable files and `-var` arguments in
  the flow keep their normal Terraform precedence.

## Who can set them

Anyone who can create a job (`team manage job`, `team plan job` and their limited variants) can set job
variables. There is no extra restriction per name. That includes teams that cannot edit the workspace
variables, so grant plan access with this in mind.

## Where the values can appear

* The plan file (`tfplan`) and `terraform show -json` contain input values, the same as for workspace
  variables. The structured plan output only reads `resource_changes` and `output_changes`, so the
  `variables` section is not part of it. Policies evaluated against `plan.json` can read the values.
* Resource attributes that are set from a variable appear in the structured output unless Terraform marks
  them sensitive, so mark secret inputs `sensitive = true`.

## Upgrading

`GET /execution/v1/capabilities` returns `jobVariables: true` on an API that supports this. During a rolling
upgrade the pod that dispatches a job is not always the one that accepted it. An older pod does not know the
new column and would run the job without the variables. Finish the rollout before clients start sending
`variables`.
