package io.terrakube.api.plugin.vcs.provider.azdevops;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookServiceBase;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.webhook.WebhookEventType;
import io.terrakube.api.rs.workspace.Workspace;

import lombok.extern.slf4j.Slf4j;

/**
 * Handles Azure DevOps integration for Terrakube webhooks.
 *
 * Azure DevOps does not expose per-repository "webhooks"; instead it uses
 * <a href="https://learn.microsoft.com/en-us/rest/api/azure/devops/hooks/subscriptions">Service Hook
 * subscriptions</a>. A single Terrakube {@link Webhook} can map to several Azure DevOps subscriptions
 * (one per event type), so the comma separated list of subscription ids is stored in
 * {@link Webhook#getRemoteHookId()}.
 *
 * Azure DevOps service hooks cannot sign the payload (no HMAC), but they can send custom HTTP headers.
 * We therefore authenticate the incoming request by comparing the {@code X-Terrakube-Token} header
 * against the base64 encoded workspace id (the same secret the other providers use), mirroring the
 * token comparison strategy used for GitLab.
 */
@Service
@Slf4j
public class AzDevOpsWebhookService extends WebhookServiceBase {

    private static final String API_VERSION = "7.0";
    private static final String HOOKS_API_VERSION = "7.0-preview.1";
    private static final String TOKEN_HEADER = "x-terrakube-token";

    // Azure DevOps service hook event types
    private static final String EVENT_PUSH = "git.push";
    private static final String EVENT_PR_CREATED = "git.pullrequest.created";
    private static final String EVENT_PR_UPDATED = "git.pullrequest.updated";
    private static final String EVENT_PR_COMMENT = "ms.vss-code.git-pullrequest-comment-event";

    private final ObjectMapper objectMapper;
    private final AzDevOpsTokenService azDevOpsTokenService;

    @Value("${io.terrakube.hostname}")
    private String hostname;
    @Value("${io.terrakube.ui.url}")
    private String uiUrl;

    public AzDevOpsWebhookService(ObjectMapper objectMapper, AzDevOpsTokenService azDevOpsTokenService) {
        this.objectMapper = objectMapper;
        this.azDevOpsTokenService = azDevOpsTokenService;
    }

    public WebhookResult processWebhook(String jsonPayload, Map<String, String> headers, String token,
            Workspace workspace) {
        WebhookResult result = new WebhookResult();
        result.setBranch("");
        result.setVia(JobVia.AzureDevops.name());
        result.setFileChanges(new ArrayList<>());
        result.setWorkspaceId(workspace.getId().toString());

        String tokenHeader = headers.get(TOKEN_HEADER);
        if (tokenHeader == null || !tokenHeader.equals(token)) {
            log.error("{} header is missing or doesn't match!", TOKEN_HEADER);
            result.setValid(false);
            return result;
        }
        result.setValid(true);

        log.info("Parsing Azure DevOps webhook payload");
        try {
            return handleEvent(jsonPayload, result, workspace);
        } catch (Exception e) {
            log.error("Error processing the Azure DevOps webhook", e);
            result.setValid(false);
            return result;
        }
    }

    public WebhookResult handleEvent(String jsonPayload, WebhookResult result, Workspace workspace) throws Exception {
        JsonNode rootNode = objectMapper.readTree(jsonPayload);
        String eventType = rootNode.path("eventType").asText();
        log.info("Azure DevOps event type: {}", eventType);

        switch (eventType) {
            case EVENT_PUSH:
                return handlePushEvent(rootNode, result, workspace);
            case EVENT_PR_CREATED:
            case EVENT_PR_UPDATED:
                return handlePullRequestEvent(rootNode, result, workspace);
            case EVENT_PR_COMMENT:
                return handlePullRequestCommentEvent(rootNode, result, workspace);
            default:
                log.error("Unsupported Azure DevOps event: {}", eventType);
                result.setValid(false);
                return result;
        }
    }

    private WebhookResult handlePushEvent(JsonNode rootNode, WebhookResult result, Workspace workspace) {
        JsonNode resource = rootNode.path("resource");
        JsonNode refUpdate = resource.path("refUpdates").path(0);
        String refName = refUpdate.path("name").asText();

        // Tag pushes (refs/tags/...) are treated as releases, branch pushes as regular pushes
        if (refName.startsWith("refs/tags/")) {
            result.setEvent("release");
            result.setRelease(true);
            result.setBranch(refName.substring("refs/tags/".length()));
        } else {
            result.setEvent("push");
            result.setBranch(stripRefPrefix(refName));
        }

        result.setCommit(refUpdate.path("newObjectId").asText());
        result.setCreatedBy(resolveUser(resource.path("pushedBy")));

        // Azure DevOps push payloads do not contain the changed files, so we collect them
        // from the API. The event's commits[] array is unreliable (often empty), so we
        // prefer diffing the pushed range oldObjectId..newObjectId.
        JsonNode repository = resource.path("repository");
        String repositoryId = repository.path("id").asText();
        AzureRepo repo = parseSource(workspace.getSource());

        if (!result.isRelease() && repo != null && !repositoryId.isEmpty()) {
            result.setFileChanges(getPushFileChanges(workspace.getVcs(), repo, repositoryId, resource, refUpdate));
            log.info("Azure DevOps push detected {} changed file(s) on branch {}: {}",
                    result.getFileChanges().size(), result.getBranch(), result.getFileChanges());
        }

        return result;
    }

    /**
     * Resolves the files changed by a push. Azure does not send them in the webhook payload, and the
     * event's {@code commits[]} array is frequently empty, so we prefer the diff of the pushed range
     * (oldObjectId..newObjectId) and fall back to per-commit / tip-commit changes.
     */
    private List<String> getPushFileChanges(Vcs vcs, AzureRepo repo, String repositoryId, JsonNode resource,
            JsonNode refUpdate) {
        String oldObjectId = refUpdate.path("oldObjectId").asText();
        String newObjectId = refUpdate.path("newObjectId").asText();
        boolean newBranch = oldObjectId == null || oldObjectId.isEmpty() || oldObjectId.chars().allMatch(c -> c == '0');

        Set<String> files = new LinkedHashSet<>();

        // Primary: diff the whole pushed range (covers every commit in the push)
        if (!newBranch && !newObjectId.isEmpty()) {
            files.addAll(getDiffChanges(vcs, repo, repositoryId, oldObjectId, newObjectId));
        }

        // Fallback 1: per-commit changes from the event payload
        if (files.isEmpty()) {
            for (JsonNode commit : resource.path("commits")) {
                String commitId = commit.path("commitId").asText();
                if (!commitId.isEmpty()) {
                    files.addAll(getCommitChanges(vcs, repo, repositoryId, commitId));
                }
            }
        }

        // Fallback 2: changes of the tip commit
        if (files.isEmpty() && !newObjectId.isEmpty()) {
            files.addAll(getCommitChanges(vcs, repo, repositoryId, newObjectId));
        }

        return new ArrayList<>(files);
    }

    private List<String> getDiffChanges(Vcs vcs, AzureRepo repo, String repositoryId, String baseCommit,
            String targetCommit) {
        List<String> changedFiles = new ArrayList<>();
        String apiUrl = String.format(
                "%s/%s/_apis/git/repositories/%s/diffs/commits?baseVersionType=commit&baseVersion=%s"
                        + "&targetVersionType=commit&targetVersion=%s&api-version=%s",
                repo.orgBaseUrl, UriUtils.encodePathSegment(repo.project, StandardCharsets.UTF_8),
                repositoryId, baseCommit, targetCommit, API_VERSION);

        ResponseEntity<String> response = callAzureApi(vcs, "", apiUrl, HttpMethod.GET);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to fetch Azure DevOps diff {}..{}", baseCommit, targetCommit);
            return changedFiles;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            for (JsonNode change : rootNode.path("changes")) {
                addChangedFile(changedFiles, change);
            }
        } catch (Exception e) {
            log.error("Error parsing Azure DevOps commit diff", e);
        }
        return changedFiles;
    }

    private WebhookResult handlePullRequestEvent(JsonNode rootNode, WebhookResult result, Workspace workspace) {
        result.setEvent("pull_request");
        JsonNode resource = rootNode.path("resource");

        int prNumber = resource.path("pullRequestId").asInt();
        result.setPrNumber(prNumber);
        result.setBranch(stripRefPrefix(resource.path("sourceRefName").asText()));
        result.setCommit(resource.path("lastMergeSourceCommit").path("commitId").asText());
        result.setCreatedBy(resolveUser(resource.path("createdBy")));

        JsonNode repository = resource.path("repository");
        String repositoryId = repository.path("id").asText();
        AzureRepo repo = parseSource(workspace.getSource());

        if (repo != null && !repositoryId.isEmpty() && prNumber > 0) {
            result.setFileChanges(getPullRequestChanges(workspace.getVcs(), repo, repositoryId, prNumber));
        }

        return result;
    }

    private WebhookResult handlePullRequestCommentEvent(JsonNode rootNode, WebhookResult result, Workspace workspace) {
        result.setEvent("issue_comment");
        JsonNode resource = rootNode.path("resource");

        String commentBody = resource.path("comment").path("content").asText().trim();
        String command = parseTerrakubeCommand(commentBody);
        if (command == null) {
            result.setValid(false);
            return result;
        }

        JsonNode pullRequest = resource.path("pullRequest");
        int prNumber = pullRequest.path("pullRequestId").asInt();

        result.setPrComment(true);
        result.setCommentBody(commentBody);
        result.setCommentCommand(command);
        result.setPrNumber(prNumber);
        result.setCreatedBy(resolveUser(resource.path("comment").path("author")));
        result.setBranch(stripRefPrefix(pullRequest.path("sourceRefName").asText()));
        result.setCommit(pullRequest.path("lastMergeSourceCommit").path("commitId").asText());

        JsonNode repository = pullRequest.path("repository");
        String repositoryId = repository.path("id").asText();
        AzureRepo repo = parseSource(workspace.getSource());

        if (repo != null && !repositoryId.isEmpty() && prNumber > 0) {
            result.setFileChanges(getPullRequestChanges(workspace.getVcs(), repo, repositoryId, prNumber));
        }

        return result;
    }

    public String createOrUpdateWebhook(Workspace workspace, Webhook webhook) {
        AzureRepo repo = parseSource(workspace.getSource());
        if (repo == null) {
            log.error("Unable to parse Azure DevOps repository from source {}", workspace.getSource());
            return "";
        }

        String[] repositoryAndProject = resolveRepository(workspace.getVcs(), repo);
        if (repositoryAndProject == null) {
            log.error("Unable to resolve Azure DevOps repository id for {}", workspace.getSource());
            return "";
        }
        String repositoryId = repositoryAndProject[0];
        String projectId = repositoryAndProject[1];

        // Recreate the subscriptions on update to honour any event configuration change.
        if (webhook.getRemoteHookId() != null && !webhook.getRemoteHookId().isEmpty()) {
            deleteWebhook(workspace, webhook.getRemoteHookId());
        }

        String secret = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhook.getId());

        Set<String> azureEventTypes = resolveAzureEventTypes(webhook);

        List<String> subscriptionIds = new ArrayList<>();
        for (String azureEventType : azureEventTypes) {
            String subscriptionId = createSubscription(workspace.getVcs(), repo, azureEventType, repositoryId,
                    projectId, webhookUrl, secret);
            if (subscriptionId != null && !subscriptionId.isEmpty()) {
                subscriptionIds.add(subscriptionId);
            }
        }

        if (subscriptionIds.isEmpty()) {
            log.error("No Azure DevOps subscriptions were created for workspace {}/{}",
                    workspace.getOrganization().getName(), workspace.getName());
            return "";
        }

        log.info("Azure DevOps service hooks created successfully for workspace {}/{} with ids {}",
                workspace.getOrganization().getName(), workspace.getName(), subscriptionIds);
        return String.join(",", subscriptionIds);
    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        AzureRepo repo = parseSource(workspace.getSource());
        if (repo == null) {
            log.warn("Unable to parse Azure DevOps repository from source {}, skipping deletion",
                    workspace.getSource());
            return;
        }

        for (String subscriptionId : webhookRemoteId.split(",")) {
            subscriptionId = subscriptionId.trim();
            if (subscriptionId.isEmpty()) {
                continue;
            }
            String apiUrl = String.format("%s/_apis/hooks/subscriptions/%s?api-version=%s",
                    repo.orgBaseUrl, subscriptionId, HOOKS_API_VERSION);
            ResponseEntity<String> response = callAzureApi(workspace.getVcs(), "", apiUrl, HttpMethod.DELETE);
            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                log.info("Azure DevOps subscription {} deleted successfully", subscriptionId);
            } else {
                log.warn("Failed to delete Azure DevOps subscription {}, message {}", subscriptionId,
                        response != null ? response.getBody() : "No response");
            }
        }
    }

    public void sendCommitStatus(Job job, JobStatus jobStatus) {
        Workspace workspace = job.getWorkspace();
        if (job.getCommitId() == null || job.getCommitId().isBlank()) {
            log.warn("No commit id available for job {}, skipping Azure DevOps commit status", job.getId());
            return;
        }

        AzureRepo repo = parseSource(workspace.getSource());
        if (repo == null) {
            log.error("Unable to parse Azure DevOps repository from source {}", workspace.getSource());
            return;
        }
        String[] repositoryAndProject = resolveRepository(workspace.getVcs(), repo);
        if (repositoryAndProject == null) {
            log.error("Unable to resolve Azure DevOps repository id for commit status on {}", workspace.getSource());
            return;
        }

        String jobUrl = String.format("%s/organizations/%s/workspaces/%s/runs/%s", uiUrl,
                workspace.getOrganization().getId(), workspace.getId(), job.getId());
        String state;
        String description;
        switch (jobStatus) {
            case completed:
                state = "succeeded";
                description = "Your task has been completed successfully.";
                break;
            case failed:
            case rejected:
            case cancelled:
                state = "failed";
                description = "Your task has failed.";
                break;
            case unknown:
                state = "error";
                description = "Your task ran into errors.";
                break;
            default:
                state = "pending";
                description = "Your task is in Terrakube queue.";
                break;
        }

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("name", workspace.getOrganization().getName() + "-" + workspace.getName());
        context.put("genre", "Terrakube");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("state", state);
        body.put("description", description);
        body.put("targetUrl", jobUrl);
        body.put("context", context);

        String apiUrl = String.format("%s/%s/_apis/git/repositories/%s/commits/%s/statuses?api-version=%s",
                repo.orgBaseUrl, UriUtils.encodePathSegment(repo.project, StandardCharsets.UTF_8),
                repositoryAndProject[0], job.getCommitId(), API_VERSION);

        try {
            ResponseEntity<String> response = callAzureApi(workspace.getVcs(), objectMapper.writeValueAsString(body),
                    apiUrl, HttpMethod.POST);
            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                log.info("Job status sent successfully to Azure DevOps for commit {}", job.getCommitId());
            } else {
                log.error("Failed to send job status to Azure DevOps, message {}",
                        response != null ? response.getBody() : "No response");
            }
        } catch (Exception e) {
            log.error("Error sending commit status to Azure DevOps", e);
        }
    }

    /**
     * Returns the current tip commit id of {@code branch}, or null if it cannot be resolved.
     * Used by outbound polling so new commits can be detected without an inbound webhook endpoint
     * (e.g. when Terrakube runs on a private network unreachable by Azure DevOps service hooks).
     */
    public String getLatestCommit(Workspace workspace, String branch) {
        AzureRepo repo = parseSource(workspace.getSource());
        if (repo == null) {
            log.error("Unable to parse Azure DevOps repository from source {}", workspace.getSource());
            return null;
        }
        String[] repositoryAndProject = resolveRepository(workspace.getVcs(), repo);
        if (repositoryAndProject == null) {
            return null;
        }

        String filter = "heads/" + UriUtils.encodeQueryParam(branch, StandardCharsets.UTF_8);
        String apiUrl = String.format("%s/%s/_apis/git/repositories/%s/refs?filter=%s&api-version=%s",
                repo.orgBaseUrl, UriUtils.encodePathSegment(repo.project, StandardCharsets.UTF_8),
                repositoryAndProject[0], filter, API_VERSION);

        ResponseEntity<String> response = callAzureApi(workspace.getVcs(), "", apiUrl, HttpMethod.GET);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to fetch Azure DevOps branch tip for {}/{}", repo.repository, branch);
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            String expectedName = "refs/heads/" + branch;
            for (JsonNode ref : rootNode.path("value")) {
                if (expectedName.equals(ref.path("name").asText())) {
                    return ref.path("objectId").asText();
                }
            }
        } catch (Exception e) {
            log.error("Error parsing Azure DevOps refs response", e);
        }
        return null;
    }

    /**
     * Builds a push {@link WebhookResult} for a commit detected by polling, resolving the files
     * changed between {@code baseCommit} (the previously seen commit, may be null) and {@code newCommit}.
     */
    public WebhookResult buildPushResult(Workspace workspace, String branch, String baseCommit, String newCommit) {
        WebhookResult result = new WebhookResult();
        result.setVia(JobVia.AzureDevops.name());
        result.setEvent("push");
        result.setValid(true);
        result.setBranch(branch);
        result.setCommit(newCommit);
        result.setCreatedBy("azuredevops-poll");
        result.setWorkspaceId(workspace.getId().toString());
        result.setFileChanges(new ArrayList<>());

        AzureRepo repo = parseSource(workspace.getSource());
        if (repo != null) {
            String[] repositoryAndProject = resolveRepository(workspace.getVcs(), repo);
            if (repositoryAndProject != null) {
                List<String> files = (baseCommit != null && !baseCommit.isEmpty())
                        ? getDiffChanges(workspace.getVcs(), repo, repositoryAndProject[0], baseCommit, newCommit)
                        : getCommitChanges(workspace.getVcs(), repo, repositoryAndProject[0], newCommit);
                // Fallback to the tip commit's own changes when the range diff yields nothing
                if (files.isEmpty() && newCommit != null && !newCommit.isEmpty()) {
                    files = getCommitChanges(workspace.getVcs(), repo, repositoryAndProject[0], newCommit);
                }
                result.setFileChanges(files);
            }
        }
        return result;
    }

    private Set<String> resolveAzureEventTypes(Webhook webhook) {
        Set<String> azureEventTypes = new LinkedHashSet<>();
        for (WebhookEvent event : webhook.getEvents()) {
            WebhookEventType eventType = event.getEvent();
            if (eventType == null) {
                continue;
            }
            switch (eventType) {
                case PUSH:
                case RELEASE:
                    // Tag (release) pushes arrive through the same git.push subscription
                    azureEventTypes.add(EVENT_PUSH);
                    break;
                case PULL_REQUEST:
                    azureEventTypes.add(EVENT_PR_CREATED);
                    azureEventTypes.add(EVENT_PR_UPDATED);
                    break;
                default:
                    break;
            }
        }

        boolean hasPrWorkflow = webhook.getEvents().stream().anyMatch(WebhookEvent::isPrWorkflowEnabled);
        if (hasPrWorkflow) {
            azureEventTypes.add(EVENT_PR_COMMENT);
        }
        return azureEventTypes;
    }

    private String createSubscription(Vcs vcs, AzureRepo repo, String azureEventType, String repositoryId,
            String projectId, String webhookUrl, String secret) {
        Map<String, Object> publisherInputs = new LinkedHashMap<>();
        publisherInputs.put("projectId", projectId);
        publisherInputs.put("repository", repositoryId);

        Map<String, Object> consumerInputs = new LinkedHashMap<>();
        consumerInputs.put("url", webhookUrl);
        consumerInputs.put("resourceDetailsToSend", "all");
        consumerInputs.put("detailedMessagesToSend", "all");
        consumerInputs.put("messagesToSend", "all");
        // Accept self-signed / internal certificates so deliveries are not dropped on TLS
        // validation (otherwise Azure puts the subscription on probation and loses events).
        consumerInputs.put("acceptUntrustedCerts", "true");
        consumerInputs.put("httpHeaders", "X-Terrakube-Token:" + secret);

        // Current Azure DevOps resource version is 2.0 for git.push and the pull request events;
        // the pull request comment event uses 1.0.
        String resourceVersion = EVENT_PR_COMMENT.equals(azureEventType) ? "1.0" : "2.0";

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("publisherId", "tfs");
        body.put("eventType", azureEventType);
        body.put("resourceVersion", resourceVersion);
        body.put("consumerId", "webHooks");
        body.put("consumerActionId", "httpRequest");
        body.put("publisherInputs", publisherInputs);
        body.put("consumerInputs", consumerInputs);

        String apiUrl = String.format("%s/_apis/hooks/subscriptions?api-version=%s", repo.orgBaseUrl,
                HOOKS_API_VERSION);

        try {
            ResponseEntity<String> response = callAzureApi(vcs, objectMapper.writeValueAsString(body), apiUrl,
                    HttpMethod.POST);
            if (response != null && response.getStatusCode().is2xxSuccessful()) {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                String id = rootNode.path("id").asText();
                log.info("Created Azure DevOps subscription {} for event {}", id, azureEventType);
                return id;
            }
            log.error("Failed to create Azure DevOps subscription for event {}, message {}", azureEventType,
                    response != null ? response.getBody() : "No response");
        } catch (Exception e) {
            log.error("Error creating Azure DevOps subscription for event {}", azureEventType, e);
        }
        return null;
    }

    /**
     * Resolves the Azure DevOps repository id and project id from the repository name in the source URL.
     *
     * @return a two element array {@code [repositoryId, projectId]} or {@code null} on failure.
     */
    private String[] resolveRepository(Vcs vcs, AzureRepo repo) {
        String apiUrl = String.format("%s/%s/_apis/git/repositories/%s?api-version=%s",
                repo.orgBaseUrl, UriUtils.encodePathSegment(repo.project, StandardCharsets.UTF_8),
                UriUtils.encodePathSegment(repo.repository, StandardCharsets.UTF_8), API_VERSION);

        ResponseEntity<String> response = callAzureApi(vcs, "", apiUrl, HttpMethod.GET);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to resolve Azure DevOps repository {}/{}", repo.project, repo.repository);
            return null;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            String repositoryId = rootNode.path("id").asText();
            String projectId = rootNode.path("project").path("id").asText();
            return new String[] { repositoryId, projectId };
        } catch (Exception e) {
            log.error("Error parsing Azure DevOps repository response", e);
            return null;
        }
    }

    private List<String> getCommitChanges(Vcs vcs, AzureRepo repo, String repositoryId, String commitId) {
        List<String> changedFiles = new ArrayList<>();
        String apiUrl = String.format("%s/%s/_apis/git/repositories/%s/commits/%s/changes?api-version=%s",
                repo.orgBaseUrl, UriUtils.encodePathSegment(repo.project, StandardCharsets.UTF_8),
                repositoryId, commitId, API_VERSION);

        ResponseEntity<String> response = callAzureApi(vcs, "", apiUrl, HttpMethod.GET);
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to fetch Azure DevOps changes for commit {}", commitId);
            return changedFiles;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            for (JsonNode change : rootNode.path("changes")) {
                addChangedFile(changedFiles, change);
            }
        } catch (Exception e) {
            log.error("Error parsing Azure DevOps commit changes", e);
        }
        return changedFiles;
    }

    private List<String> getPullRequestChanges(Vcs vcs, AzureRepo repo, String repositoryId, int prNumber) {
        List<String> changedFiles = new ArrayList<>();
        String encodedProject = UriUtils.encodePathSegment(repo.project, StandardCharsets.UTF_8);
        String iterationsUrl = String.format(
                "%s/%s/_apis/git/repositories/%s/pullRequests/%s/iterations?api-version=%s",
                repo.orgBaseUrl, encodedProject, repositoryId, prNumber, API_VERSION);

        ResponseEntity<String> iterationsResponse = callAzureApi(vcs, "", iterationsUrl, HttpMethod.GET);
        if (iterationsResponse == null || !iterationsResponse.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to fetch Azure DevOps iterations for pull request {}", prNumber);
            return changedFiles;
        }

        int latestIteration = -1;
        try {
            JsonNode rootNode = objectMapper.readTree(iterationsResponse.getBody());
            for (JsonNode iteration : rootNode.path("value")) {
                latestIteration = Math.max(latestIteration, iteration.path("id").asInt());
            }
        } catch (Exception e) {
            log.error("Error parsing Azure DevOps pull request iterations", e);
            return changedFiles;
        }

        if (latestIteration < 0) {
            return changedFiles;
        }

        String changesUrl = String.format(
                "%s/%s/_apis/git/repositories/%s/pullRequests/%s/iterations/%s/changes?api-version=%s",
                repo.orgBaseUrl, encodedProject, repositoryId, prNumber, latestIteration, API_VERSION);

        ResponseEntity<String> changesResponse = callAzureApi(vcs, "", changesUrl, HttpMethod.GET);
        if (changesResponse == null || !changesResponse.getStatusCode().is2xxSuccessful()) {
            log.error("Failed to fetch Azure DevOps changes for pull request {}", prNumber);
            return changedFiles;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(changesResponse.getBody());
            for (JsonNode change : rootNode.path("changeEntries")) {
                addChangedFile(changedFiles, change);
            }
        } catch (Exception e) {
            log.error("Error parsing Azure DevOps pull request changes", e);
        }
        return changedFiles;
    }

    private void addChangedFile(List<String> changedFiles, JsonNode change) {
        JsonNode item = change.path("item");
        // Skip folder entries, Terrakube path filters operate on files
        if (item.path("isFolder").asBoolean(false) || "tree".equals(item.path("gitObjectType").asText())) {
            return;
        }
        String path = item.path("path").asText();
        if (path == null || path.isEmpty()) {
            return;
        }
        // Azure DevOps paths are absolute (start with '/'), align them with the other providers
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (!changedFiles.contains(path)) {
            changedFiles.add(path);
        }
    }

    private ResponseEntity<String> callAzureApi(Vcs vcs, String body, String apiUrl, HttpMethod httpMethod) {
        String token = resolveToken(vcs);
        if (token == null || token.isEmpty()) {
            log.error("No Azure DevOps access token available for vcs {}", vcs.getId());
            return null;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + token);

        try {
            return makeApiRequest(headers, body, apiUrl, httpMethod);
        } catch (RestClientException e) {
            log.error("Error calling Azure DevOps API {}: {}", apiUrl, e.getMessage());
            return null;
        }
    }

    private String resolveToken(Vcs vcs) {
        if (vcs.getVcsType() == VcsType.AZURE_SP_MI) {
            return azDevOpsTokenService.getAzureDefaultToken();
        }
        return vcs.getAccessToken();
    }

    private String resolveUser(JsonNode userNode) {
        String uniqueName = userNode.path("uniqueName").asText();
        if (uniqueName != null && !uniqueName.isEmpty()) {
            return uniqueName;
        }
        String displayName = userNode.path("displayName").asText();
        return displayName == null || displayName.isEmpty() ? "system" : displayName;
    }

    private String stripRefPrefix(String ref) {
        if (ref == null) {
            return "";
        }
        if (ref.startsWith("refs/heads/")) {
            return ref.substring("refs/heads/".length());
        }
        if (ref.startsWith("refs/tags/")) {
            return ref.substring("refs/tags/".length());
        }
        return ref;
    }

    /**
     * Parses the org scoped base url, project and repository name out of a Terrakube Azure DevOps source url.
     *
     * Supported shapes (with or without the {@code _git} segment and an optional {@code .git} suffix):
     * <ul>
     *   <li>{@code https://dev.azure.com/{org}/{project}/_git/{repo}}</li>
     *   <li>{@code https://dev.azure.com/{org}/{project}/{repo}}</li>
     *   <li>{@code https://{org}.visualstudio.com/{project}/_git/{repo}}</li>
     *   <li>{@code https://{org}.visualstudio.com/{project}/{repo}}</li>
     * </ul>
     */
    protected AzureRepo parseSource(String source) {
        try {
            String cleaned = source.replaceAll("\\.git$", "");
            URI uri = new URI(cleaned);
            String host = uri.getHost();
            String scheme = uri.getScheme();
            if (host == null || scheme == null) {
                return null;
            }
            // Build host[:port] WITHOUT the userinfo component. Azure DevOps clone urls
            // frequently include a userinfo (e.g. https://org@dev.azure.com/...), but the
            // HTTP client rejects a request URI whose authority carries the deprecated
            // userinfo component, so it must be stripped here (getAuthority keeps it).
            int port = uri.getPort();
            String hostPort = host + (port != -1 ? ":" + port : "");

            List<String> segments = new ArrayList<>();
            for (String segment : uri.getPath().split("/")) {
                if (!segment.isEmpty() && !"_git".equals(segment)) {
                    segments.add(segment);
                }
            }

            String orgBaseUrl;
            String project;
            String repository;
            if (host.endsWith("visualstudio.com")) {
                // org is the subdomain, path is {project}/{repo}
                orgBaseUrl = scheme + "://" + hostPort;
                if (segments.size() < 2) {
                    return null;
                }
                project = segments.get(0);
                repository = segments.get(segments.size() - 1);
            } else {
                // dev.azure.com or Azure DevOps Server, path is {org}/{project}/{repo}
                if (segments.size() < 3) {
                    return null;
                }
                orgBaseUrl = scheme + "://" + hostPort + "/" + segments.get(0);
                project = segments.get(1);
                repository = segments.get(segments.size() - 1);
            }
            return new AzureRepo(orgBaseUrl, project, repository);
        } catch (Exception e) {
            log.error("Error extracting the Azure DevOps repository from {}", source, e);
            return null;
        }
    }

    /** Holds the org scoped base url, project and repository name parsed from a source url. */
    protected static class AzureRepo {
        final String orgBaseUrl;
        final String project;
        final String repository;

        AzureRepo(String orgBaseUrl, String project, String repository) {
            this.orgBaseUrl = orgBaseUrl;
            this.project = project;
            this.repository = repository;
        }
    }
}
