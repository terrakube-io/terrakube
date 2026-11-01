resource "google_project_service" "services" {
  count   = length(var.gcp_service_list)
  service = var.gcp_service_list[count.index]
}

resource "random_string" "random" {
  length  = 3
  special = false
  lower   = true
  upper   = false
  numeric = false
}

locals {
  workload_identity_pool_id          = format("terrakube-pool%s", random_string.random.result)
  workload_identity_pool_provider_id = format("terrakube-provider-%s", random_string.random.result)
}

resource "google_iam_workload_identity_pool" "terrakube_pool" {
  workload_identity_pool_id = local.workload_identity_pool_id
}

resource "google_iam_workload_identity_pool_provider" "terrakube_provider" {
  workload_identity_pool_id          = google_iam_workload_identity_pool.terrakube_pool.workload_identity_pool_id
  workload_identity_pool_provider_id = local.workload_identity_pool_provider_id
  attribute_mapping = {
    "google.subject"                      = "assertion.sub",
    "attribute.aud"                       = "assertion.aud",
    "attribute.terrakube_workspace_id"    = "assertion.terrakube_workspace_id",
    "attribute.terrakube_organization_id" = "assertion.terrakube_organization_id",
    "attribute.terrakube_job_id"          = "assertion.terrakube_job_id"
  }
  oidc {
    issuer_uri = "https://${var.terrakube_hostname}"
  }
  # Match the subject exactly (a prefix match would also accept "<workspace>2") and pin the
  # workspace id, so a workspace that later takes this name does not inherit the identity.
  attribute_condition = "assertion.sub == \"organization:${var.terrakube_organization_name}:workspace:${var.terrakube_workspace_name}\" && assertion.terrakube_workspace_id == \"${terrakube_workspace_cli.dynamic_credentials_workspace.id}\""

  depends_on = [google_iam_workload_identity_pool.terrakube_pool]
}

resource "google_service_account" "terrakube_service_account" {
  account_id   = "terrakube-service-account${random_string.random.result}"
  display_name = "Service Account${random_string.random.result}"
}

resource "google_service_account_iam_member" "terrakube_service_account_member" {
  service_account_id = google_service_account.terrakube_service_account.name
  role               = "roles/iam.workloadIdentityUser"
  member             = "principalSet://iam.googleapis.com/${google_iam_workload_identity_pool.terrakube_pool.name}/*"
}

resource "google_project_iam_member" "terrakube_project_member" {
  project = var.gcp_project_id
  role    = "roles/editor"
  member  = "serviceAccount:${google_service_account.terrakube_service_account.email}"
}

resource "terrakube_workspace_cli" "dynamic_credentials_workspace" {
  organization_id = data.terrakube_organization.org.id
  name            = var.terrakube_workspace_name
  description     = "Sample description"
  execution_mode  = "remote"
  iac_type        = "terraform"
  iac_version     = "1.5.7"

  depends_on = [data.terrakube_organization.org]
}


resource "terrakube_workspace_variable" "env1" {
  organization_id = data.terrakube_organization.org.id
  workspace_id    = terrakube_workspace_cli.dynamic_credentials_workspace.id
  key             = "ENABLE_DYNAMIC_CREDENTIALS_GCP"
  value           = "true"
  description     = "ENABLE_DYNAMIC_CREDENTIALS_GCP"
  category        = "ENV"
  sensitive       = false
  hcl             = false
}


resource "terrakube_workspace_variable" "env2" {
  organization_id = data.terrakube_organization.org.id
  workspace_id    = terrakube_workspace_cli.dynamic_credentials_workspace.id
  key             = "WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL"
  value           = google_service_account.terrakube_service_account.email
  description     = "WORKLOAD_IDENTITY_SERVICE_ACCOUNT_EMAIL"
  category        = "ENV"
  sensitive       = false
  hcl             = false
}


resource "terrakube_workspace_variable" "env3" {
  organization_id = data.terrakube_organization.org.id
  workspace_id    = terrakube_workspace_cli.dynamic_credentials_workspace.id
  key             = "WORKLOAD_IDENTITY_AUDIENCE_GCP"
  value           = "//iam.googleapis.com/projects/${data.google_project.project.number}/locations/global/workloadIdentityPools/${google_iam_workload_identity_pool.terrakube_pool.workload_identity_pool_id}/providers/${google_iam_workload_identity_pool_provider.terrakube_provider.workload_identity_pool_provider_id}"
  description     = "WORKLOAD_IDENTITY_AUDIENCE_GCP"
  category        = "ENV"
  sensitive       = false
  hcl             = false
}