# Azure DevOps VCS provider

Adds Azure DevOps (`dev.azure.com` and Azure DevOps Server) as a VCS provider for Terrakube
workspaces. It supports both VCS connection types already used elsewhere in Terrakube:

- `AZURE_DEVOPS` — OAuth / Personal Access Token connection.
- `AZURE_SP_MI` — Service Principal / Managed Identity (token resolved via `DefaultAzureCredential`).

There are two ways to start a run when code changes: inbound **service hooks** (default) and outbound
**commit polling** (opt-in).

## Inbound service hooks (default)

When a workspace with an Azure DevOps VCS is saved, Terrakube creates an Azure DevOps
[service hook](https://learn.microsoft.com/azure/devops/service-hooks/overview) subscription that
posts to the Terrakube webhook endpoint. Supported events:

| Event | `eventType` | `resourceVersion` |
|-------|-------------|-------------------|
| Code pushed | `git.push` | `2.0` |
| Pull request created | `git.pullrequest.created` | `2.0` |
| Pull request updated | `git.pullrequest.updated` | `2.0` |
| Pull request comment | `ms.vss-code.git-pullrequest-comment-event` | `1.0` |

Each delivery is authenticated with a per-webhook `x-terrakube-token` header. Commit status is
reported back to Azure DevOps as the run progresses.

Service hooks are delivered from the Azure DevOps cloud, so the Terrakube webhook endpoint must be
reachable from the public internet. The service principal / token must have **Edit Subscriptions**
permission to create them.

## Outbound commit polling (opt-in)

If Terrakube runs on a **private network** that the Azure DevOps cloud cannot reach, inbound service
hooks will silently fail (and Azure DevOps eventually disables the subscription). Polling reverses the
direction: Terrakube periodically makes an **outbound** call to fetch the tip commit of each Azure
DevOps workspace branch and starts a run when it changes. It reuses the same credentials already used
for cloning, so **no inbound network exposure is required**.

Polling runs every workspace check concurrently each cycle, so detection latency stays close to the
configured interval regardless of how many workspaces are tracked. The last-seen commit per
workspace/branch is stored in Redis (`azdo-poll:{workspaceId}:{branch}`); the first observation only
records a baseline and does not trigger a run.

### Configuration

Polling is **disabled by default**. Enable it with the following environment variables (Spring
properties in parentheses):

| Environment variable | Property | Default | Description |
|----------------------|----------|---------|-------------|
| `AzureDevOpsPollingEnabled` | `io.terrakube.vcs.azuredevops.polling.enabled` | `false` | Enable outbound commit polling. |
| `AzureDevOpsPollingInterval` | `io.terrakube.vcs.azuredevops.polling.interval` | `15000` | Milliseconds between polls of each workspace. Lower to detect commits sooner; raise to reduce API traffic. |
| `AzureDevOpsPollingInitialDelay` | `io.terrakube.vcs.azuredevops.polling.initial-delay` | `10000` | Milliseconds to wait after startup before the first poll. |

Example (Helm `values.yaml`, API environment):

```yaml
env:
  - name: AzureDevOpsPollingEnabled
    value: "true"
  - name: AzureDevOpsPollingInterval
    value: "15000"
```

When polling is enabled, leave the workspace's branch configured normally; Terrakube polls that
branch. Event matching (branch and file-path filters on the workspace webhook) is applied exactly as
it is for inbound pushes.
