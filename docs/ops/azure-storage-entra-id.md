# Azure Blob Storage without an account key

The api, registry and executor can reach Azure Blob Storage with Microsoft Entra ID instead of the storage account key, for example with AKS Workload Identity or a managed identity. A configured key is used exactly as before.

## Configuration

Leave the key empty. The other storage settings stay as they are, and the account name must be set:

| Component | Leave empty | Account name |
| --- | --- | --- |
| api, registry | `AzureAccountKey` | `AzureAccountName` |
| executor | `AzureTerraformStateStorageAccessKey` and `AzureTerraformStateStorageAccessKeyFile` | `AzureTerraformStateStorageAccountName` |

Without a key, each component connects to `https://<account>.blob.core.windows.net` with `DefaultAzureCredential`, which takes the identity from Workload Identity (`AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_FEDERATED_TOKEN_FILE`), a managed identity, the `AZURE_*` variables of a service principal or the Azure CLI. A missing account name stops the component at startup.

## Role assignments

Give the api, registry and executor identities `Storage Blob Data Contributor` on the storage account. Assign it on the account rather than on single containers: the components create their containers on first use, and the registry's user delegation key is an account level operation.

## Terraform state

How Terraform and OpenTofu reach the state depends on `TerraformStateHttpBackend` on the executor.

With `TerraformStateHttpBackend=true`, the state goes through the Terrakube API, which reads and writes it with its own identity. Terraform needs no access to the storage account.

With `TerraformStateHttpBackend=false` (the default), the generated `azure_backend_override.tf` sets `use_azuread_auth = true` instead of `access_key`. Terraform then signs in to the state container with the identity that the `ARM_*` variables of the run describe (see the [azurerm backend documentation](https://developer.hashicorp.com/terraform/language/backend/azurerm)). Set them on the executor, for example `ARM_USE_AKS_WORKLOAD_IDENTITY=true` for Workload Identity, or `ARM_USE_MSI=true` (with `ARM_CLIENT_ID` for a user-assigned identity) for a managed identity. That identity needs `Storage Blob Data Contributor` on the state container, which the assignment above already covers when it is the executor identity.

Workspace variables and Azure dynamic credentials are added on top of the executor environment. A workspace that brings its own Azure credentials (`ARM_CLIENT_ID`, `ARM_CLIENT_SECRET` or `ARM_OIDC_TOKEN`, or Azure dynamic credentials, which set `ARM_CLIENT_ID` and `ARM_OIDC_TOKEN` and can use another client for plans through `ARM_CLIENT_ID_PLAN`) therefore also uses them for the shared state container:

* `terraform init` fails with a 403 unless that identity has data access on the state container, and an identity from another tenant usually cannot be given that access.
* With `ARM_USE_AKS_WORKLOAD_IDENTITY=true` on the executor, a workspace `ARM_CLIENT_ID` is paired with the executor's federated token and sign-in fails.

If workspaces use their own Azure credentials, set `TerraformStateHttpBackend=true`. With an account key the backend uses `access_key` and is not affected by `ARM_*` variables.

## Registry download redirect

With `AzureBlobPresignedRedirectEnabled=true` and no key, the `module.zip` redirect carries a user delegation SAS instead of a service SAS. The registry requests a user delegation key once and reuses it until it would expire before a new SAS; each key is valid for the SAS expiry plus one hour. Requesting the key needs `Microsoft.Storage/storageAccounts/blobServices/generateUserDelegationKey/action` on the storage account, which `Storage Blob Data Contributor` includes. See [Registry direct storage download](registry-direct-storage-download.md) for the redirect itself.
