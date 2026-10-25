<div id="terrakube" align="center">
    <br />
    <img src="https://avatars.githubusercontent.com/u/80990539?s=200&v=4" alt="Terrakube logo" width="100"/>
    <h1>Terrakube</h1>
    <p>Open source IaC Automation and Collaboration Software.</p>
</div>

<div id="badges" align="center">

[![Build](https://github.com/terrakube-io/terrakube/actions/workflows/pull_request.yml/badge.svg)](https://github.com/terrakube-io/terrakube/actions/workflows/pull_request.yml)
[![Quality gate](https://sonarcloud.io/api/project_badges/measure?project=AzBuilder_azb-server&metric=alert_status)](https://sonarcloud.io/dashboard?id=AzBuilder_azb-server)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=AzBuilder_azb-server&metric=coverage)](https://sonarcloud.io/dashboard?id=AzBuilder_azb-server)
[![Release](https://img.shields.io/github/v/release/terrakube-io/terrakube)](https://github.com/terrakube-io/terrakube/releases/latest)
[![Docker pulls](https://img.shields.io/docker/pulls/azbuilder/api-server?label=docker%20pulls)](https://hub.docker.com/r/azbuilder/api-server)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/terrakube-io/terrakube/blob/main/LICENSE)
[![OpenSSF Scorecard](https://api.securityscorecards.dev/projects/github.com/terrakube-io/terrakube/badge)](https://securityscorecards.dev/viewer/?uri=github.com/terrakube-io/terrakube)
[![Documentation](https://raw.githubusercontent.com/aleen42/badges/master/src/gitbook_2.svg)](https://docs.terrakube.io/)
[![Slack](https://img.shields.io/badge/Join%20Our%20Community-Slack-blue)](https://join.slack.com/t/terrakubeworkspace/shared_invite/zt-2cx6yn95t-2CTBGvsQhBQJ5bfbG4peFg)

</div>

## Try it without installing anything

Open Terrakube in a GitHub Codespace to test it from your browser:

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://github.com/codespaces/new/terrakube-io/terrakube)

## Features

- **Organizations and Workspaces:** Use Organizations and Workspaces to manage your infrastructure in a structured and efficient way. Workspaces also support Tags, which let you group, organize, and filter your workspaces based on the tags you assign to them.

<img src="docs/images/readme/workspaces.png" alt="The Terrakube workspace list grouped by project, showing run status, last run time, Terraform version and source repository" width="1080"/>

- **Private Registry:** Publish your Terraform Modules and Providers privately.

<img src="docs/images/readme/registry.png" alt="The Terrakube private registry listing six modules with their provider, latest version and download count" width="1080"/>

- **Version Control Integration:** Terrakube integrates with GitHub (Cloud and Enterprise), GitLab (EE and CE), Bitbucket and Azure DevOps to retrieve your terraform/opentofu code.

<img src="docs/images/readme/vcs.png" alt="The VCS providers settings page with GitHub, GitLab, Bitbucket and Azure DevOps connections" width="1080"/>

- **Terraform Workflow:** Terrakube allows you to run the Terraform / OpenTofu workflow from anywhere, anytime. You can perform actions such as terraform apply, terraform plan, and terraform destroy. You can also manage variables and access the Terraform state files.

<img src="docs/images/readme/workflow.png" alt="A completed Terrakube run with the structured plan result showing four resources to create and an expanded resource change" width="1080"/>

- **Custom Workflows:** Enhance your IaC workflow with Infracost, or any other tool of your choice. You can use Terrakube extensions to integrate them seamlessly, or create your own custom integration using the Terrakube API. This way, you can automate compliance checks, cost estimates, security scans, and more for your Terraform projects.

- **Policy as Code:** Evaluate [OPA](https://www.openpolicyagent.org/) policies against every plan before it is applied. Policy sets run at an advisory, soft mandatory or hard mandatory level, and exemptions can be scoped to an organization, project or workspace. See [Policy Enforcement](https://docs.terrakube.io/user-guide/policy-enforcement-opa).

- **Access Control:** You can use [Dex](https://github.com/dexidp/dex) to authenticate in Terrakube with various identity providers, such as Azure Active Directory, Amazon Cognito, GitHub, SAML, and more. You can also leverage your existing groups to assign granular permissions to Workspaces, Modules, VCS, and other resources.

- **Remote Backend:** Terrakube supports both `remote backend` and `cloud` block so you can run your workflow directly from the Terraform / OpenTofu CLI.

- **Dynamic Credentials:** Give runs short-lived access to AWS, Azure, GCP or Vault through workload identity, so you do not store long-lived cloud keys in workspaces.

- **Flexible Executors:** Run on the built-in executor, on self-hosted agents, or as ephemeral Kubernetes jobs that exist only for the length of a run.

- **Drift Detection and Schedules:** Run plans on a schedule to catch drift, and chain workspaces with run triggers.

- **Pull Request and CI Integration:** Webhooks from GitHub, GitLab, Bitbucket and Azure DevOps trigger plans for your pull requests, and Terrakube posts the result back to the pull request on GitHub, GitLab and Bitbucket. You can also run Terrakube jobs from your pipelines with the [Terrakube GitHub Action](https://github.com/terrakube-io/terrakube-action-github).

- **Manage Terrakube as Code:** Use the [Terraform provider](https://registry.terraform.io/providers/terrakube-io/terrakube/latest), the Terrakube CLI or the API to configure organizations, workspaces and policies.

## Use it from the Terraform CLI

Point the `cloud` block at your Terrakube API and run Terraform as usual. The run executes in Terrakube and the output streams back to your terminal.

```hcl
terraform {
  cloud {
    hostname     = "terrakube-api.example.com"
    organization = "acme"

    workspaces {
      name = "hello-terrakube"
    }
  }
}
```

Run `terraform login terrakube-api.example.com` once, then `terraform plan` or `terraform apply`. More in [CLI-driven workflow](https://docs.terrakube.io/user-guide/workspaces/cli-driven-workflow).

## How it fits together

```mermaid
flowchart TB
    subgraph clients[" Clients "]
        browser[Browser]
        cli[Terraform or OpenTofu CLI]
    end
    subgraph terrakube[" Terrakube "]
        ui[UI]
        api[API]
        registry[Registry]
        executor[Executor]
    end
    subgraph data[" Data "]
        db[(Database)]
        redis[(Redis)]
        storage[(Object storage)]
    end
    dex[Dex and your identity provider]
    browser --> ui --> api
    cli --> api
    cli --> registry
    api -->|runs each step| executor
    api -->|sign in| dex
    api --> db
    executor -->|live output| redis
    executor -->|state and output| storage
```

The executor writes the live output to Redis and the state and final output to object storage, and the API reads both back. Terrakube stores state and run output in Amazon S3 (or any S3-compatible service such as MinIO), Azure Blob Storage, Google Cloud Storage or local disk. The database is PostgreSQL or Azure SQL (H2 is available for local testing).

## Getting started

- [Install Terrakube using Helm](https://docs.terrakube.io/getting-started/deployment)
- [Install Terrakube using Docker Compose](https://docs.terrakube.io/getting-started/docker-compose)
- [Test Terrakube using minikube](https://docs.terrakube.io/getting-started/deployment/minikube-+-https)
- [Test Terrakube using Gitpod](https://docs.terrakube.io/getting-started/getting-started)
- [Develop Terrakube locally using Dev Containers](.devcontainer/README.md)

## Documentation

The user and administrator documentation is at [docs.terrakube.io](https://docs.terrakube.io/). Operational runbooks and reference pages that go with the code are in the [docs](docs/README.md) folder of this repository.

## Security

Please report vulnerabilities privately to security@terrakube.io. See [SECURITY.md](SECURITY.md) for what to include.

## Contributing

Terrakube welcomes any idea or feedback from the community. If you want to contribute to this project, please read our [Contribution Guide](CONTRIBUTING.md) for more details.

## Sponsors

| Sponsor  | Thanks |
| ------------- | ------------- |
| [<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg" alt="JetBrains" width="32"> JetBrains](https://jb.gg/OpenSource)  | For providing with free licenses to their great tools.   |
| [<img src="https://uploads-ssl.webflow.com/5c349f90a3cd4515d0564552/5c66e5b48238e30e170da3be_logo.svg" alt="GitBook" width="32"> GitBook](https://www.gitbook.com/)   | For providing us with free OSS Plan. |
| [<img src="https://github.com/terrakube-io/terrakube/assets/27365102/e5977550-eb4f-4519-9aa8-293e5660f873" alt="Docker" width="32"> Docker](https://www.docker.com/) | For providing us with free OSS Plan.|
| [<img src="https://github.com/user-attachments/assets/c094496d-ff2d-4501-8416-8185b1abe45a" alt="Tuta" width="32"> Tuta](https://tuta.com/) | For providing us with free email service.|

## Terraform BSL License

Hashicorp confirmed that Terrakube is compatible with the new Terraform BSL License, more information can be found in the following [discussion](https://github.com/orgs/terrakube-io/discussions/467).
