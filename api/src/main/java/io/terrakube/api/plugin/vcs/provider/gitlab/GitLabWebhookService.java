package io.terrakube.api.plugin.vcs.provider.gitlab;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.job.JobStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookServiceBase;
import io.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class GitLabWebhookService extends WebhookServiceBase {

    private ObjectMapper objectMapper;
    private String hostname;
    private WebClient.Builder webClientBuilder;
    private String uiUrl;

    public GitLabWebhookService(ObjectMapper objectMapper, @Value("${io.terrakube.hostname}") String hostname, @Value("${io.terrakube.ui.url}") String uiUrl, WebClient.Builder webClientBuilder) {
        this.objectMapper = objectMapper;
        this.hostname = hostname;
        this.webClientBuilder = webClientBuilder;
        this.uiUrl = uiUrl;
    }

    public WebhookResult processWebhook(String jsonPayload, Map<String, String> headers, String token) {
        WebhookResult result = new WebhookResult();
        result.setBranch("");
        result.setVia("GitLab");
        try {
            // Verify the GitLab token
            String tokenHeader = headers.get("x-gitlab-token");
            if (tokenHeader == null || !tokenHeader.equals(token)) {
                log.error("X-Gitlab-Token header is missing or doesn't match!");
                result.setValid(false);
                return result;
            }

            result.setValid(true);

            log.info("Parsing GitLab webhook payload");

            // Extract event
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            String event = rootNode.path("object_kind").asText();
            result.setEvent(event);

            if (event.equals("push")) {
                // Extract branch from the ref
                String[] ref = rootNode.path("ref").asText().split("/");
                String[] extractedBranch = Arrays.copyOfRange(ref, 2, ref.length);
                result.setBranch(String.join("/", extractedBranch));

                // Extract the user who triggered the webhook
                JsonNode userNode = rootNode.path("user_username");
                String user = userNode.asText();
                result.setCreatedBy(user);

                result.setFileChanges(new ArrayList());
                try {
                    GitlabWebhookModel gitlabWebhookModel = new ObjectMapper().readValue(jsonPayload, GitlabWebhookModel.class);
                    result.setCommit(gitlabWebhookModel.getCheckoutSha());
                    gitlabWebhookModel.getCommits().forEach(commitData -> {

                        for (String gitlabmodified : commitData.getModified()) {
                            result.getFileChanges().add(gitlabmodified);
                            log.info("Modified Gitlab Object: {}", gitlabmodified);
                        }

                        for (String gitlabRemoved : commitData.getRemoved()) {
                            result.getFileChanges().add(gitlabRemoved);
                            log.info("Removed Gitlab Object: {}", gitlabRemoved);
                        }

                        for (String gitlabAdded : commitData.getAdded()) {
                            log.info("New Gitlab Object: {}", gitlabAdded);
                            result.getFileChanges().add(gitlabAdded);
                        }
                    });
                } catch (JsonProcessingException e) {
                    log.error(e.getMessage());
                }

            }
        } catch (JsonProcessingException e) {
            log.error("Error parsing JSON payload", e);
        }
        return result;
    }

    public String createWebhook(Workspace workspace, String webhookId) {
        String id = "";
        String secret = Base64.getEncoder()
                .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));

        String ownerAndRepo = extractOwnerAndRepoGitlab(workspace.getSource());
        String token = workspace.getVcs().getAccessToken();
        String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhookId);
        RestTemplate restTemplate = new RestTemplate();

        // Create the headers
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Content-Type", "application/json");
        headers.set("Authorization", "Bearer " + workspace.getVcs().getAccessToken());

        // Create the body
        String body = "{\"url\":\"" + webhookUrl
                + "\",\"push_events\":\"true\",\"enable_ssl_verification\":\"false\",\"token\":\"" + secret + "\"}";

        log.info(body);
        // Create the entity
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        String projectId = "";
        try {
            log.info("Search gitlab project id using {}, {}", ownerAndRepo, workspace.getVcs().getApiUrl());
            projectId = getGitlabProjectId(ownerAndRepo, token, workspace.getVcs().getApiUrl());
        } catch (InterruptedException | IOException e) {
            log.error(e.getMessage());
            Thread.currentThread().interrupt();
        }
        URI gitlabUri = UriComponentsBuilder.fromHttpUrl(workspace.getVcs().getApiUrl() + "/projects/" + projectId + "/hooks").build(true).toUri();

        // Make the request using the GitLab API
        ResponseEntity<String> response = restTemplate.exchange(
                gitlabUri, HttpMethod.POST, entity, String.class);

        // Extract the id from the response
        if (response.getStatusCode().value() == 201) {
            ObjectMapper objectMapper = new ObjectMapper();
            try {
                JsonNode rootNode = objectMapper.readTree(response.getBody());
                id = rootNode.path("id").asText();
            } catch (Exception e) {
                log.error("Error parsing JSON response", e);
            }

            log.info("GitHub Hook created successfully for workspace {}/{} with id {}",
                    workspace.getOrganization().getName(), workspace.getName(), id);
        }

        return id;
    }

    public String getGitlabProjectId(String ownerAndRepo, String accessToken, String gitlabBaseUrl) throws IOException, InterruptedException {
        AtomicReference<String> projectId = new AtomicReference<>("");

        WebClient webClient = webClientBuilder
                .baseUrl(gitlabBaseUrl)
                .defaultHeader("Authorization", "Bearer " + accessToken)
                .defaultHeader("Content-Type", "application/json")
                .build();

        AtomicInteger currentPage = new AtomicInteger(1);
        AtomicBoolean projectFound = new AtomicBoolean(false);
        AtomicBoolean hasMorePages = new AtomicBoolean(true);
        
        while (hasMorePages.get() && !projectFound.get()) {
            try {
                webClient.get()
                        .uri(uriBuilder -> uriBuilder
                                .path("/projects")
                                .queryParam("membership", "true")
                                .queryParam("per_page", "1")
                                .queryParam("page", currentPage)
                                .build())
                        .exchangeToMono(response -> {
                            if (response.statusCode().is2xxSuccessful()) {

                                List<String> nextPageHeaders = response.headers().header("x-next-page");
                                String nextPageHeader = nextPageHeaders.isEmpty() ? null : nextPageHeaders.get(0);

                                return response.bodyToMono(String.class)
                                        .doOnNext(responseBody -> {
                                            try {

                                                JsonNode jsonNode = objectMapper.readTree(responseBody);

                                                for (JsonNode element : jsonNode) {
                                                    if (element.get("path_with_namespace").asText().equals(ownerAndRepo)) {
                                                        projectId.set(element.get("id").asText());
                                                        projectFound.set(true);
                                                        break;
                                                    }
                                                }

                                                if (nextPageHeader == null || nextPageHeader.isEmpty()) {
                                                    hasMorePages.set(false);
                                                } else {
                                                    currentPage.set(Integer.parseInt(nextPageHeader));
                                                }
                                                
                                                log.debug("Processed page {}, hasMorePages={}, projectFound={}", currentPage.get() -1, hasMorePages, projectFound);
                                            } catch (Exception e) {
                                                log.error("Error parsing response: {}", e.getMessage());
                                            }
                                        });
                            } else {
                                log.error("Failed to retrieve project ID. HTTP Status: {}", response.statusCode());
                                hasMorePages.set(false);
                                return Mono.empty();
                            }
                        })
                        .block();
            
            } catch (Exception e) {
                log.error("Failed to retrieve project ID. Error: {}", e.getMessage());
                hasMorePages.set(false);
            }
        }
        
        if (projectFound.get()) {
            log.info("Parsed Project ID: {}", projectId);
        } else {
            log.warn("Project with path {} not found after checking all pages", ownerAndRepo);
        }
        
        return projectId.get();
    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        String ownerAndRepo = extractOwnerAndRepoGitlab(workspace.getSource());
        String apiUrl = workspace.getVcs().getApiUrl() + "/projects/" + ownerAndRepo + "/hooks/" + webhookRemoteId;

        ResponseEntity<String> response = callGitlabApi(workspace.getVcs().getAccessToken(), "", apiUrl, HttpMethod.DELETE);
        if (response.getStatusCode().value() == 204) {
            log.info("Webhook with remote hook id {} on repository {} deleted successfully", webhookRemoteId, ownerAndRepo);
        } else {
            log.warn("Failed to delete webhook with remote hook id {} on repository {}, message {}", webhookRemoteId, ownerAndRepo, response.getBody());
        }
    }

    private ResponseEntity<String> callGitlabApi(String token, String body, String apiUrl, HttpMethod httpMethod) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Authorization", "Bearer " + token);
        headers.set("Content-Type", "application/json");

        ResponseEntity<String> response = makeApiRequest(headers, body, apiUrl, httpMethod);

        return response;
    }

    public void sendCommitStatus(Job job, JobStatus jobStatus) {
        Workspace workspace = job.getWorkspace();
        String jobUrl = String.format("%s/organizations/%s/workspaces/%s/runs/%s", uiUrl,
                workspace.getOrganization().getId(), workspace.getId(), job.getId());
        String ownerAndRepos = extractOwnerAndRepoGitlab(workspace.getSource());

        try {
            String projectId = getGitlabProjectId(ownerAndRepos, job.getWorkspace().getVcs().getAccessToken(), job.getWorkspace().getVcs().getApiUrl());
            GitlabCommitStatus commitStatus = GitlabCommitStatus.pending;
            String commitStatusContext = "Terrakube - " + workspace.getOrganization().getName() + " - "
                    + workspace.getName();
            String commitStatusDescription = "Your task is in Terrakube queue.";

            // Determine the commit status based on jobStatus
            switch (jobStatus) {
                case completed:
                    commitStatus = GitlabCommitStatus.success;
                    commitStatusDescription = "Your task has been completed successfully.";
                    break;
                case failed:
                case rejected:
                case cancelled:
                    commitStatus = GitlabCommitStatus.failed;
                    commitStatusDescription = "Your task has failed.";
                    break;
                case unknown:
                    commitStatus = GitlabCommitStatus.failed;
                    commitStatusDescription = "Your task ran into errors.";
                    break;
                default:
                    break;
            }

            // Create WebClient instance
            WebClient webClient = webClientBuilder
                    .baseUrl(job.getWorkspace().getVcs().getApiUrl())
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, "application/json")
                    .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                    .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + job.getWorkspace().getVcs().getAccessToken())
                    .build();

            // Create request body
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("state", commitStatus.toString());
            requestBody.put("name", commitStatusContext);
            requestBody.put("target_url", jobUrl);
            requestBody.put("description", commitStatusDescription);

            // Send POST request
            String response = webClient.post()
                    .uri("/projects/{id}/statuses/{sha}", projectId, job.getCommitId())
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            log.info("Commit status sent to GitLab: {}", response);

        } catch (Exception e) {
            log.error("Error sending commit status to GitLab", e);
            Thread.currentThread().interrupt();
        }

    }
}