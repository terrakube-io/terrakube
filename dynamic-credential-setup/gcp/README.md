# Setup Terrakube Dynamic Credentials (GCP)

## Requirements

Make sure to mount your public and private key to the API container as explained [here](https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials#generate-public-and-private-key)

> Mare sure the private key is in ***"pkcs8"*** format

Validate the following endpoints are working:

- https://terrakube-api.mydomain.com/.well-known/jwks
- https://terrakube-api.mydomain.com/.well-known/openid-configuration

Set terraform variables using: ***"variables.auto.tfvars"***

```terraform
terrakube_token = "TERRAKUBE_PERSONAL_ACCESS_TOKEN"
terrakube_hostname = "terrakube-api.mydomain.com"
terrakube_organization_name = "simple"
terrakube_workspace_name = "dynamic-workspace"
gcp_project_id = "my-gcp-project"
```

> To generate the API token check [here](https://docs.terrakube.io/user-guide/organizations/api-tokens)

Run Terraform apply to create all the federated credential setup in GCP and a sample workspace in terrakube for testing

To test the following terraform code can be used:


```terraform
terraform {

  cloud {
    organization = "terrakube_organization_name"
    hostname = "terrakube-api.mydomain.com"

    workspaces {
      name = "terrakube_workspace_name"
    }
  }
}

provider "google" {
  project     = "my-gcp-project"
  region      = "us-central1"
  zone        = "us-central1-c"
}

resource "google_storage_bucket" "auto-expire" {
  name          = "mysuperbukcetname"
  location      = "US"
  force_destroy = true

  public_access_prevention = "enforced"
}
```
## Project claim

When the workspace belongs to a project, the token also carries a `terrakube_project_name` claim. You can map it to an attribute and scope access by project — for example, map it in the workload identity pool provider:

```terraform
attribute_mapping = {
  "google.subject"                   = "assertion.sub",
  "attribute.terrakube_project_name" = "assertion.terrakube_project_name"
}
```

and reference `attribute.terrakube_project_name` in an `attribute_condition` or in the service account IAM binding to grant access only to workspaces in a given project. The claim is omitted when the workspace has no project, so conditions that require it will not match those workspaces.

## Separating Plan and Apply Identities (`run_phase`)

When `DynamicCredentialRunPhaseEnabled` (`io.terrakube.dynamic.credentials.run-phase.enabled`, default `true`) is enabled, every dynamic credential token carries a `terrakube_run_phase` claim (`"plan"` or `"apply"`), and Terrakube injects `TERRAKUBE_RUN_PHASE` and `TF_VAR_terrakube_run_phase` into the step environment. Only `terraformApply` and `terraformDestroy` receive `"apply"`; `terraformPlan`, `terraformPlanDestroy`, `customScripts`, and `policyEvaluation` receive `"plan"`.

Set `WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL_PLAN` on the workspace to impersonate a read-only service account on plan steps (falling back to `WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL` when unset), and map `assertion.terrakube_run_phase` in your Workload Identity Pool provider so GCP enforces which identity can be impersonated per phase:

```terraform
attribute_mapping = {
  "google.subject"                   = "assertion.sub",
  "attribute.terrakube_workspace_id" = "assertion.terrakube_workspace_id",
  "attribute.terrakube_run_phase"    = "assertion.terrakube_run_phase"
}
```

You can then bind the apply service account with a `roles/iam.workloadIdentityUser` member scoped to `attribute.terrakube_run_phase/apply` (or check `assertion.terrakube_run_phase == "apply"` in an `attribute_condition`).

> Note: GCP limits `google.subject` to 127 bytes. Mapping `assertion.terrakube_run_phase` keeps `sub` unchanged (`organization:<org>:workspace:<ws>`). If you also set `ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE=true` on the workspace, Terrakube appends `:run_phase:<phase>` (+16 bytes) to `sub`.
