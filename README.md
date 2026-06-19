<div id="terrakube" align="center">
    <br />
    <img src="https://avatars.githubusercontent.com/u/80990539?s=200&v=4" alt="Terrakube Logo" width="100"/>
    <h2 border="0">Terrakube</h2>
    <p>Open source IaC Automation and Collaboration Software.</p>
</div>

<div id="badges" align="center">

[![Build](https://github.com/terrakube-io/terrakube/actions/workflows/pull_request.yml/badge.svg)](https://github.com/terrakube-io/terrakube/actions/workflows/pull_request.yml)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=AzBuilder_azb-server&metric=coverage)](https://sonarcloud.io/dashboard?id=AzBuilder_azb-server)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/terrakube-io/terrakube/blob/main/LICENSE)
[![gitbook](https://raw.githubusercontent.com/aleen42/badges/master/src/gitbook_2.svg)](https://gitpod.io/#https://github.com/terrakube-io/terrakube)
[![Slack](https://img.shields.io/badge/Join%20Our%20Community-Slack-blue)](https://join.slack.com/t/terrakubeworkspace/shared_invite/zt-2cx6yn95t-2CTBGvsQhBQJ5bfbG4peFg)


</div>

## GitHub Development Environment

To quickly test Terrakube without having to install anything in your computer use the following:

[![Open in GitHub Codespaces](https://github.com/codespaces/badge.svg)](https://github.com/codespaces/new/terrakube-io/terrakube)

### Features
The key features of Terrakube are:
- **Private Registry:** Publish your Terraform Modules and Providers privately.   <br/>

<img src="https://github.com/terrakube-io/terrakube/assets/27365102/66fbd39c-0a40-43d8-94d9-4c2a0976051c" width="1080"/>  <br/>

- **Organizations and Workspaces:** Use Organizations and Workspaces to manage your infrastructure in a structured and efficient way. Workspaces also support Tags, which let you group, organize, and filter your workspaces based on the tags you assign to them.    <br/>

<img src="https://github.com/terrakube-io/terrakube/assets/27365102/f36953f7-0dbd-4877-be8d-ba2bf7704f2b" width="1080"/>  <br/>

- **Version Control Integration:** Terrakube integrates with GitHub (Cloud and  Enterprise), GitLab (EE and CE), Bitbucket and Azure DevOps to retrieve your terraform/opentofu code. <br/>

<img src="https://github.com/terrakube-io/terrakube/assets/27365102/d9102910-41af-42be-b154-1257108f688b" width="1080"/>  <br/>

- **Terraform Workflow:** Terrakube allows you to run the Terraform / OpenTofu workflow from anywhere, anytime. You can perform actions such as terraform apply, terraform plan, and terraform destroy. You can also manage variables and access the Terraform state files.

<img src="https://github.com/terrakube-io/terrakube/assets/27365102/9e0421be-576c-4206-a29a-c1d62238681e" width="1080"/>  <br/>

- **Custom Workflows:** Enhance your IaC workflow with OPA, Infracost, or any other tool of your choice. You can use Terrakube extensions to integrate them seamlessly, or create your own custom integration using the Terrakube API. This way, you can automate compliance checks, cost estimates, security scans, and more for your Terraform projects.

- **Access Control:** You can use [Dex](https://github.com/dexidp/dex) to authenticate in Terrakube with various identity providers, such as Azure Active Directory, Amazon Cognito, GitHub, SAML, and more. You can also leverage your existing groups to assign granular permissions to Workspaces, Modules, VCS, and other resources.

- **Remote Backend:** Terrakube supports both `remote backend` and `cloud` block so you can run your workflow directly from the Terraform / OpenTofu CLI.

- **Import providers from private registries:** Mirror custom Terraform/OpenTofu providers into the Terrakube private registry from any Terraform provider registry (resolved through service discovery — works with GitLab, Artifactory/JFrog, TFE and self-hosted registries) or from a plain repository release page / web server that hosts goreleaser-style assets. Private sources can be authenticated with a bearer token, and versions are refreshed automatically in the background.

### Getting Started

### Installation

- [Install Terrakube using Helm](https://docs.terrakube.io/getting-started/deployment/docker-compose)
- [Install Terrakube using Docker Compose](https://docs.terrakube.io/getting-started/docker-compose)
- [Test Terrakube using minikube](https://docs.terrakube.io/getting-started/deployment/minikube-+-https)
- [Test Terrakube using Gitpod](https://docs.terrakube.io/getting-started/getting-started)
- [Develop Terrakube using VS Code Dev Containers](.devcontainer/README.md)

### Importing providers from a private registry

Terrakube can mirror a provider published outside the public Terraform Registry into your organization's private registry, so `terraform init` / `tofu init` can install it from Terrakube. Open **Registry → Publish → Import provider from private registry** in the UI (or POST a `provider` with `imported=true` via the API). Two source types are supported:

**1. Terraform provider registry (`sourceType=TERRAFORM_REGISTRY`)**

Any registry that implements the [Terraform provider registry protocol](https://developer.hashicorp.com/terraform/internals/provider-registry-protocol). The host is resolved through service discovery (`/.well-known/terraform.json`), so this works with GitLab, Artifactory/JFrog, Terraform Enterprise and self-hosted registries.

| Field | Description |
| --- | --- |
| `name` | Provider type, e.g. `mycloud`. |
| `registryHost` | Registry host, e.g. `gitlab.example.com`. Leave empty to use the public `registry.terraform.io`. |
| `registryNamespace` | Namespace/organization of the provider in that registry. |
| `registryToken` | Optional bearer token sent as `Authorization: Bearer …`. |

**2. Repository / web page (`sourceType=REPOSITORY`)**

A repository release page (GitHub/GitLab releases) or any web server hosting [goreleaser](https://goreleaser.com/)-style assets — the standard output of the [terraform-provider scaffolding](https://github.com/hashicorp/terraform-provider-scaffolding-framework):

```
terraform-provider-<name>_<version>_<os>_<arch>.zip
terraform-provider-<name>_<version>_SHA256SUMS
terraform-provider-<name>_<version>_SHA256SUMS.sig
```

| Field | Description |
| --- | --- |
| `name` | Provider type, must match the asset filenames. |
| `repositoryUrl` | Base URL holding the assets. Use `{version}` where the version/tag appears in the path, e.g. `https://github.com/acme/terraform-provider-mycloud/releases/download/v{version}`. |
| `repositoryVersions` | Comma-separated versions to import, e.g. `1.0.0,1.1.0`. |
| `gpgKeyId` / `gpgAsciiArmor` | GPG key id and ASCII-armored public key used to sign `SHA256SUMS`. Required for Terraform to verify the provider on install. |
| `registryToken` | Optional bearer token for private repositories. |

Platforms and shasums are discovered from the `SHA256SUMS` file, so only the versions you list need to be specified. In both modes a background job (`ProviderRefreshJob`) imports new versions on creation and re-checks every 24 hours; existing public-registry imports are unaffected (`registryHost` empty + `sourceType=TERRAFORM_REGISTRY` keeps the previous behavior).

### Documentation
To learn more about Terrakube [go to the complete documentation.](https://docs.terrakube.io/) 

### Contributing 
Terrakube welcomes any idea or feedback from the community. If you want to contribute to this project, please read our [Contribution Guide](CONTRIBUTING.md) for more details.

### Sponsors

| Sponsor  | Thanks |
| ------------- | ------------- |
| [<img src="https://resources.jetbrains.com/storage/products/company/brand/logos/jb_beam.svg" alt="JetBrains" width="32"> JetBrains](https://jb.gg/OpenSource)  | For providing with free licenses to their great tools.   |
| [<img src="https://uploads-ssl.webflow.com/5c349f90a3cd4515d0564552/5c66e5b48238e30e170da3be_logo.svg" alt="GitBook" width="32"> GitBook](https://www.gitbook.com/)   | For providing us with free OSS Plan. |
| [<img src="https://github.com/terrakube-io/terrakube/assets/27365102/e5977550-eb4f-4519-9aa8-293e5660f873" width="32"> Docker](https://www.docker.com/) | For providing us with free OSS Plan.|
| [<img src="https://github.com/user-attachments/assets/c094496d-ff2d-4501-8416-8185b1abe45a" width="32"> Tuta](https://tuta.com/) | For providing us with free email service.|


### Terraform BSL License
Hashicorp confirmed that Terrakube is compatible with the new Terraform BSL License, more information can be found in the following [discussion](https://github.com/orgs/terrakube-io/discussions/467).
