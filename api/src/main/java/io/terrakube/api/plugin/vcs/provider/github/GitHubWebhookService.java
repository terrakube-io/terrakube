package io.terrakube.api.plugin.vcs.provider.github;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.eclipse.jgit.api.errors.GitAPIException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import io.terrakube.api.plugin.vcs.TokenService;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookServiceBase;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import io.terrakube.api.rs.job.JobVia;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.webhook.Webhook;
import io.terrakube.api.rs.webhook.WebhookEvent;
import io.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class GitHubWebhookService extends WebhookServiceBase {

    private final ObjectMapper objectMapper;
    private final TokenService tokenService;

    @Value("${io.terrakube.hostname}")
    private String hostname;
    @Value("${io.terrakube.ui.url}")
    private String uiUrl;

    public GitHubWebhookService(ObjectMapper objectMapper, TokenService tokenService) {
        this.objectMapper = objectMapper;
        this.tokenService = tokenService;
    }

    public WebhookResult processWebhook(String jsonPayload, Map<String, String> headers, String token, Vcs vcs) {
        return handleWebhook(jsonPayload, headers, token, "x-hub-signature-256", JobVia.Github.name(),
                (payload, result, headerMap) -> handleEvent(payload, result, headerMap, vcs));
    }

    private WebhookResult handleEvent(String jsonPayload, WebhookResult result, Map<String, String> headers, Vcs vcs) {
        String event = headers.get("x-github-event");
        result.setEvent(event);
        if (event.equals("ping")) {
            return result;
        }

        JsonNode rootNode = null;
        try {
            rootNode = objectMapper.readTree(jsonPayload);
        } catch (Exception e) {
            log.error("Error parsing JSON response", e);
        }

        if (rootNode == null) {
            log.error("Error fetching root node from JSON payload");
            return result;
        }

        // Handle push event
        if ("push".equals(event)) {
            // Extract branch from the ref
            String[] ref = rootNode.path("ref").asText().split("/");
            String[] extractedBranch = Arrays.copyOfRange(ref, 2, ref.length);
            result.setBranch(String.join("/", extractedBranch));

            // Extract the user who triggered the webhook
            String pusher = rootNode.path("pusher").path("email").asText();
            result.setCreatedBy(pusher);

            // Extract files changed in the push
            List<String> fileChanges = new ArrayList<>();
            try {
                GitHubWebhookModel gitHubWebhookModel = objectMapper.readValue(jsonPayload, GitHubWebhookModel.class);
                result.setCommit(gitHubWebhookModel.getHead_commit().getId());
                gitHubWebhookModel.getCommits().forEach(commit -> {
                    fileChanges.addAll(commit.getAdded());
                    fileChanges.addAll(commit.getRemoved());
                    fileChanges.addAll(commit.getModified());
                });
                result.setFileChanges(fileChanges);

            } catch (Exception e) {
                log.error("Error parsing JSON response", e);
            }
            // Handle pull request event (opened, synchronize, reopened)
        } else if ("pull_request".equals(event)) {
            // Extract repository owner and name from the payload
            String gitPath = rootNode.path("repository").path("clone_url").asText();
            String action = rootNode.path("action").asText();
            if ("opened".equals(action) || "synchronize".equals(action) || "reopened".equals(action)) {
                int prNumber = rootNode.path("number").asInt();
                result.setPrNumber(prNumber);

                String prCommitId = rootNode.path("pull_request").path("head").path("sha").asText();
                result.setCommit(prCommitId);

                String prBranch = rootNode.path("pull_request").path("head").path("ref").asText();
                result.setBranch(prBranch);

                // Set createdBy to the user who created the PR
                String prUser = rootNode.path("pull_request").path("user").path("login").asText();
                result.setCreatedBy(prUser);
                
                String prFilesUrl = rootNode.path("pull_request").path("url").asText() + "/files";
                // Fetch file changes for the PR
                List<String> prFileChanges = getPrFileChanges(vcs, gitPath, prFilesUrl);
                result.setFileChanges(prFileChanges);
            } else {
                result.setValid(false);
                log.error("No valid github pull request event: {} ", action);
            }
        } else if ("release".equals(event)) {
            String action = rootNode.path("action").asText();
            if ("created".equals(action)){
                log.info("Received release created webhook event for repository {}", rootNode.path("repository").path("full_name"));
                result.setValid(true);
                result.setRelease(true);
                result.setBranch(rootNode.path("release").path("tag_name").asText());
            } else {
                result.setValid(false);
                log.error("No valid github release event: {}", action);
            }
        } else {
            result.setValid(false);
            log.error("No valid github event " + result.getEvent());
        }

        return result;
    }

    public void sendCommitStatus(Job job, JobStatus jobStatus) {
        Workspace workspace = job.getWorkspace();
        String jobUrl = String.format("%s/organizations/%s/workspaces/%s/runs/%s", uiUrl,
                workspace.getOrganization().getId(), workspace.getId(), job.getId());
        String[] ownerAndRepos = extractOwnerAndRepo(workspace.getSource());

        GithubCommitStatus commitStatus = GithubCommitStatus.pending;
        String commitStatusContext = "Terrakube - " + workspace.getOrganization().getName() + " - "
                + workspace.getName();
        String commitStatusDescription = "Your task is in Terrakube queue.";

        // Determine the commit status based on jobStatus
        switch (jobStatus) {
            case completed:
                commitStatus = GithubCommitStatus.success;
                commitStatusDescription = "Your task has been completed successfully.";
                break;
            case failed:
            case rejected:
            case cancelled:
                commitStatus = GithubCommitStatus.failure;
                commitStatusDescription = "Your task has failed.";
                break;
            case unknown:
                commitStatus = GithubCommitStatus.error;
                commitStatusDescription = "Your task ran into errors.";
                break;
            default:
                break;
        }

        // API URL for commit status
        String apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepos) + "/statuses/"
                + job.getCommitId();

        log.info(String.format("Sending job status %s to GitHub for commit %s", job.getStatus(), job.getCommitId()));

        // Create the body for the commit status
        String body = "{\"state\":\"" + commitStatus.name()
                + "\",\"description\":\"" + commitStatusDescription + "\",\"target_url\":\""
                + jobUrl + "\",\"context\":\"" + commitStatusContext + "\"}";

        ResponseEntity<String> response = callGitHubApi(workspace.getVcs(), workspace.getSource(), body, apiUrl,
                HttpMethod.POST);

        // Handle the response
        if (response == null) {
            log.error("Failed to send job status on workspace {} in organization {} to GitHub", workspace.getName(),
                    workspace.getOrganization().getName());
            return;
        }

        if (response.getStatusCode().value() == 201) {
            log.info("Job status sent successfully to GitHub");
        } else {
            log.error(String.format("Failed to send job status to Github, message %s", response.getBody()));
        }

        // Optional: Check if the commit is part of a PR and send status to the PR as
        // well
        try {
            List<Integer> prNumbers = getPullRequestNumbersForCommit(workspace, job.getCommitId());
            for (Integer prNumber : prNumbers) {
                String prApiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepos)
                        + "/pulls/" + prNumber + "/statuses";

                // Send the status to the pull request
                ResponseEntity<String> prResponse = callGitHubApi(workspace.getVcs(), workspace.getSource(), body, prApiUrl,
                        HttpMethod.POST);
                if (prResponse == null) {
                    log.error("Failed to send job status on PR #{} in workspace {} to GitHub", prNumber,
                            workspace.getName());
                    continue;
                }

                if (prResponse.getStatusCode().value() == 201) {
                    log.info("Job status sent successfully to PR #{} on GitHub", prNumber);
                } else {
                    log.error(String.format("Failed to send job status to PR #%, message %s", prNumber,
                            prResponse.getBody()));
                }
            }
        } catch (Exception e) {
            log.error("Error occurred while checking PRs for commit {}: {}", job.getCommitId(), e.getMessage());
        }
    }

    private List<Integer> getPullRequestNumbersForCommit(Workspace workspace, String commitId) {
        List<Integer> prNumbers = new ArrayList<>();
        String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());
        String apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo)
                + "/commits/" + commitId + "/pulls";

        ResponseEntity<String> response = callGitHubApi(workspace.getVcs(), workspace.getSource(), null, apiUrl,
                HttpMethod.GET);

        if (response != null && response.getStatusCode().value() == 200) {
            try {
                JsonNode jsonNode = new ObjectMapper().readTree(response.getBody());
                for (JsonNode pr : jsonNode) {
                    prNumbers.add(pr.path("number").asInt());
                }
            } catch (Exception e) {
                log.error("Failed to parse PR data for commit {}: {}", commitId, e.getMessage());
            }
        } else {
            log.error("Failed to fetch PRs for commit {}: {}", commitId,
                    response != null ? response.getBody() : "No response");
        }

        return prNumbers;
    }

    private List<String> getPrFileChanges(Vcs vcs, String gitPath, String apiUrl) {
        List<String> changedFiles = new ArrayList<>();

        ResponseEntity<String> response = callGitHubApi(vcs, gitPath, "", apiUrl, HttpMethod.GET);
        if(response == null || response.getStatusCode().value() != 200) {
            log.error("Failed to fetch PR file changes from GitHub, response: {}", response != null ? response.getBody() : "No response");
            return changedFiles;
        }
        
        try {
            JsonNode rootNode = objectMapper.readTree(response.getBody());
            for (JsonNode file : rootNode) {
                changedFiles.add(file.path("filename").asText());
            }
        } catch (Exception e) {
            log.error("Error parsing JSON response", e);
        }
        return changedFiles;
    }

    public String createOrUpdateWebhook(Workspace workspace, Webhook webhook) {
        String id = webhook.getRemoteHookId();
        String secret = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
        String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhook.getId().toString());
        String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());

        // Only Push and Pull Request events are supported for now
        String events = webhook.getEvents().stream().map(WebhookEvent::getEvent).distinct()
                .map(s -> "\"" + String.valueOf(s).toLowerCase() + "\"")
                .collect(Collectors.joining(","));
        String body = "";
        String apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo) + "/hooks";
        HttpMethod httpMethod = HttpMethod.POST;

        if (id != null) {
            body = "{\"active\":true, \"events\":[" + events + "]}";
            apiUrl = apiUrl + "/" + webhook.getRemoteHookId();
            httpMethod = HttpMethod.PATCH;
        } else {
            body = "{\"name\":\"web\",\"active\":true,\"events\":[" + events + "],\"config\":{\"url\":\""
                    + webhookUrl
                    + "\",\"secret\":\"" + secret + "\",\"content_type\":\"json\",\"insecure_ssl\":\"1\"}}";
        }

        ResponseEntity<String> response = callGitHubApi(workspace.getVcs(), workspace.getSource(), body, apiUrl,
                httpMethod);
        // Extract the id from the response
        if (response != null && (response.getStatusCode().value() == 201 || response.getStatusCode().value() == 200)) {
            if (id == null) {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode rootNode = objectMapper.readTree(response.getBody());
                    id = rootNode.path("id").asText();
                } catch (Exception e) {
                    log.error("Error parsing JSON response", e);
                }
            }

            log.info("GitHub Hook created/updated successfully for workspace {}/{} with id {}",
                    workspace.getOrganization().getName(), workspace.getName(), id);
        }

        return id;

    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        String apiUrl = webhookRemoteId;
        String ownerAndRepo[] = extractOwnerAndRepo(workspace.getSource());

        // Previously the remote_hook_id is the whole URL of the webhook, hence the
        // below check. This can be removed in a major version upgrade.
        if (!webhookRemoteId.substring(0, 4).equals("http")) {
            apiUrl = workspace.getVcs().getApiUrl() + "/repos/" + String.join("/", ownerAndRepo) + "/hooks/"
                    + webhookRemoteId;
        }

        ResponseEntity<String> response = callGitHubApi(workspace.getVcs(), workspace.getSource(), "", apiUrl,
                HttpMethod.DELETE);
        if (response == null) {
            log.error("Failed to delete webhook with remote hook id {} on repository {}/{}", webhookRemoteId,
                    ownerAndRepo[0], ownerAndRepo[1]);
            return;
        }

        if (response.getStatusCode().value() == 204) {
            log.info("Webhook with remote hook id {} on repository {} deleted successfully", webhookRemoteId,
                    workspace.getSource());
        } else {
            log.warn("Failed to delete webhook with remote hook id {} on repository {}, message {}", webhookRemoteId,
                    workspace.getSource(), response.getBody());
        }
    }

    private ResponseEntity<String> callGitHubApi(Vcs vcs, String gitPath, String body, String apiUrl,
            HttpMethod httpMethod) {
        String token = "";
        try {
            token = tokenService.getAccessToken(gitPath, vcs);
        } catch (JsonProcessingException | NoSuchAlgorithmException | InvalidKeySpecException | GitAPIException | URISyntaxException e) {
            log.error("Error retrieving tokens for access to owner/organization {}, error {}", gitPath, e);
            return null;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/vnd.github+json");
        headers.set("Authorization", "Bearer " + token);
        headers.set("X-GitHub-Api-Version", "2022-11-28");

        ResponseEntity<String> response = makeApiRequest(headers, body, apiUrl, httpMethod);

        return response;
    }
}
