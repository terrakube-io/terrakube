# Setup Terrakube Dynamic Credentials (Azure)

## Requirements

Make sure to mount your public and private key to the API container as explained [here](https://docs.terrakube.io/user-guide/workspaces/dynamic-provider-credentials#generate-public-and-private-key)

> Mare sure the private key is in ***"pkcs8"*** format

Validate the following endpoints are working:

- https://terrakube-api.mydomain.com/.well-known/jwks
- https://terrakube-api.mydomain.com/.well-known/openid-configuration

Set terraform variables using: ***"variables.auto.tfvars"***

```terraform
terrakube_token                          = "TERRAKUBE_PERSONAL_ACCESS_TOKEN"
terrakube_api_hostname                   = "TERRAKUBE-API.MYCLUSTER.COM"
terrakube_federated_credentials_audience = "api://AzureADTokenExchange"
terrakube_organization_name              = "simple"
terrakube_workspace_name                 = "dynamic-azure"
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

provider "azurerm" {
  features {}
}

 resource "azurerm_resource_group" "example" {
  name     = "randomstring-aejthtyu"
  location = "East US 2"
}
```

## Separating Plan and Apply Identities (`run_phase`)

Azure federated identity credentials require an exact `subject` match. You can assign a read-only Entra ID application / managed identity to plan steps while reserving write access for apply and destroy steps:

1. Ensure the instance-level kill switch `DynamicCredentialRunPhaseEnabled` (`io.terrakube.dynamic.credentials.run-phase.enabled`) is `true` (its default).
2. On the workspace, set:
   - `ENABLE_DYNAMIC_CREDENTIALS_RUN_PHASE=true` — when set strictly to `"true"` (case-insensitive; other values such as `no` or `off` leave it disabled), appends `:run_phase:<phase>` (`:run_phase:plan` or `:run_phase:apply`) to the JWT `sub` claim.
   - `ARM_CLIENT_ID_PLAN` — the read-only client ID used for `plan` steps (falls back to `ARM_CLIENT_ID` when unset).
3. Create separate federated identity credentials for each phase on the corresponding Entra applications:

```terraform
resource "azuread_application_federated_identity_credential" "plan" {
  application_id = azuread_application_registration.plan_app.id
  display_name   = "terrakube-plan"
  audiences      = [var.terrakube_federated_credentials_audience]
  issuer         = "https://${var.terrakube_api_hostname}"
  subject        = "organization:${var.terrakube_organization_name}:workspace:${var.terrakube_workspace_name}:run_phase:plan"
}

resource "azuread_application_federated_identity_credential" "apply" {
  application_id = azuread_application_registration.apply_app.id
  display_name   = "terrakube-apply"
  audiences      = [var.terrakube_federated_credentials_audience]
  issuer         = "https://${var.terrakube_api_hostname}"
  subject        = "organization:${var.terrakube_organization_name}:workspace:${var.terrakube_workspace_name}:run_phase:apply"
}
```

Terrakube also injects `TERRAKUBE_RUN_PHASE` and `TF_VAR_terrakube_run_phase` (`"plan"` or `"apply"`) into every step of a workspace with dynamic credentials enabled. `terraformApply`, `terraformDestroy`, and `customScripts` steps that follow an apply or destroy step in the same flow receive `"apply"`; `terraformPlan`, `terraformPlanDestroy`, `policyEvaluation`, and pre-apply or standalone `customScripts` steps receive `"plan"`.