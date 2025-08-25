package io.terrakube.api.plugin.vcs.provider.bitbucket;

import com.fasterxml.jackson.core.JsonProcessingException;
import io.terrakube.api.rs.webhook.Webhook;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import io.terrakube.api.plugin.vcs.WebhookResult;
import io.terrakube.api.plugin.vcs.WebhookServiceBase;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.workspace.Workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.DefaultUriBuilderFactory;

import java.io.BufferedReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@Service
@Slf4j
public class BitBucketWebhookService extends WebhookServiceBase {

    @Autowired
    private WebClient.Builder webClientBuilder;

    private final ObjectMapper objectMapper;

    @Value("${io.terrakube.hostname}")
    private String hostname;

    private WorkspaceRepository workspaceRepository;

    public BitBucketWebhookService(WorkspaceRepository workspaceRepository, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.workspaceRepository = workspaceRepository;
    }

    public WebhookResult processWebhook(String jsonPayload, Map<String, String> headers, String token) {
        return handleWebhook(jsonPayload, headers, token, "x-hub-signature", "Bitbucket", this::handleEvent);
    }

    public WebhookResult handleEvent(String jsonPayload, WebhookResult result, Map<String, String> headers) {
        // Extract event
        String event = headers.get("x-event-key");
        log.info("Bitbucket event: {}", event);

        if (event.equals("repo:push")) {
            return handlePushEvent(jsonPayload, result);
        } else if (event.equals("pullrequest:created") || event.equals("pullrequest:updated")) {
            return handlePullRequestEvent(jsonPayload, result);
        } else {
            log.error("Unsupported Bitbucket event: {}", result.getEvent());
            result.setValid(false);
        }

        return result;
    }

    private WebhookResult handlePushEvent(String jsonPayload, WebhookResult result) {
        result.setEvent("push");
        try {
            // Extract branch from the changes
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            JsonNode changesNode = rootNode.path("push").path("changes").get(0);

            // Check if this is a tag creation event
            String changeType = changesNode.path("new").path("type").asText();
            String targetType = changesNode.path("new").path("target").path("type").asText();

            log.info("Bitbucket change type is empty: {}", changeType.isEmpty());
            if (changeType.equals("tag")) {
                return handleTagCreationEvent(jsonPayload, result);
            } else if (changeType.equals("branch") && targetType.equals("commit")) {
                return handlePushCommit(jsonPayload, result, changesNode);
            } else {
                log.error("Unsupported Bitbucket change type: {}", changeType);
                result.setBranch("");
            }

        } catch (Exception e) {
            log.error("Error parsing push event JSON response", e);
            result.setBranch("");
        }

        return result;
    }

    private WebhookResult handlePushCommit(String jsonPayload, WebhookResult result, JsonNode changesNode) throws JsonProcessingException {
        String ref = changesNode.path("new").path("name").asText();
        result.setBranch(ref);

        JsonNode authorNode = changesNode.path("new").path("target").path("author").path("raw");
        String author = authorNode.asText();
        author = author.substring(author.indexOf("<") + 1, author.indexOf(">"));
        result.setCreatedBy(author);

        BitbucketTokenModel bitbucketTokenModel = new ObjectMapper().readValue(jsonPayload,
                BitbucketTokenModel.class);
        if (bitbucketTokenModel.getPush().getChanges().size() == 1) {
            log.info("Bitbucket commit: {}",
                    bitbucketTokenModel.getPush().getChanges().get(0).getNewCommit().getTarget().getHash());
            log.info("Bitbucket diff file: {}",
                    bitbucketTokenModel.getPush().getChanges().get(0).getLinks().getDiff().getHref());
            result.setCommit(
                    bitbucketTokenModel.getPush().getChanges().get(0).getNewCommit().getTarget().getHash());
            result.setFileChanges(getFileChanges(
                    bitbucketTokenModel.getPush().getChanges().get(0).getLinks().getDiff().getHref(),
                    result.getWorkspaceId()));
        } else {
            log.error("Bitbucket webhook with more than 1 changes is not supported");
        }

        return result;
    }

    private WebhookResult handlePullRequestEvent(String jsonPayload, WebhookResult result) {
        result.setEvent("pull_request");
        try {
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            JsonNode pullRequestNode = rootNode.path("pullrequest");

            String sourceBranch = pullRequestNode.path("source").path("branch").path("name").asText();
            result.setBranch(sourceBranch);

            result.setCreatedBy("no-reply@terrakube.io");

            String commit = pullRequestNode.path("source").path("commit").path("hash").asText();
            result.setCommit(commit);

            JsonNode linksNode = pullRequestNode.path("links");
            if (linksNode.has("diff")) {
                String diffUrl = linksNode.path("diff").path("href").asText();
                result.setFileChanges(getFileChanges(diffUrl, result.getWorkspaceId()));
            }
            
            log.info("Bitbucket pull request event processed for branch: {}", sourceBranch);
        } catch (Exception e) {
            log.error("Error parsing pull request event JSON response", e);
            result.setBranch("");
        }

        return result;
    }

    private WebhookResult handleTagCreationEvent(String jsonPayload, WebhookResult result) {
        result.setEvent("release");
        try {
            JsonNode rootNode = objectMapper.readTree(jsonPayload);
            JsonNode changesNode = rootNode.path("push").path("changes").get(0);
            JsonNode newNode = changesNode.path("new");

            String tagName = newNode.path("name").asText();
            result.setBranch(tagName);
            result.setRelease(true);

            JsonNode targetNode = newNode.path("target");
            if (targetNode.has("hash")) {
                String commit = targetNode.path("hash").asText();
                result.setCommit(commit);
            }

            JsonNode authorNode = targetNode.path("author");
            if (authorNode.has("raw")) {
                String author = authorNode.path("raw").asText();
                author = author.substring(author.indexOf("<") + 1, author.indexOf(">")); //extract email only
                result.setCreatedBy(author);
            } else {
                result.setCreatedBy("Unknown");
            }

            result.setValid(true);

            log.info("Bitbucket tag creation event processed for tag: {}", tagName);
        } catch (Exception e) {
            log.error("Error parsing tag creation event JSON response", e);
            result.setBranch("");
        }
        return result;
    }

    private List<String> getFileChanges(String diffFile, String workspaceId) {
        List<String> fileChanges = new ArrayList<>();

        try {
            String accessToken = "Bearer "
                    + workspaceRepository.findById(UUID.fromString(workspaceId)).get().getVcs().getAccessToken();

            WebClient webClient = webClientBuilder
                    .uriBuilderFactory(createNoEncodingUriBuilderFactory())
                    .defaultHeader(HttpHeaders.AUTHORIZATION, accessToken)
                    .build();

            log.info("Bitbucket diff: {}", diffFile);

            String diffContent = webClient.get()
                    .uri(diffFile)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            new BufferedReader(new StringReader(diffContent)).lines().forEach(line -> {
                if (line.startsWith("diff --git ")) {
                    log.warn("Checking change: {}", line);
                    // Example
                    // diff --git a/work1/main.tf b/work1/main.tf
                    String file = line.split("\\s+")[2].substring(2);
                    log.warn("Adding file: {}", file);
                    fileChanges.add(file);
                }
            });
        } catch (Exception e) {
            log.error(e.getMessage());
        }

        return fileChanges;
    }

    private DefaultUriBuilderFactory createNoEncodingUriBuilderFactory() {
        DefaultUriBuilderFactory factory = new DefaultUriBuilderFactory();
        factory.setEncodingMode(DefaultUriBuilderFactory.EncodingMode.NONE);
        return factory;
    }


    public String createOrUpdateWebhook(Workspace workspace, Webhook webhook) {
        String remoteHookId = webhook.getRemoteHookId();
        if (remoteHookId == null || remoteHookId.isEmpty()) {
            String secret = Base64.getEncoder()
                    .encodeToString(workspace.getId().toString().getBytes(StandardCharsets.UTF_8));
            String[] ownerAndRepo = extractOwnerAndRepo(workspace.getSource());
            String webhookUrl = String.format("https://%s/webhook/v1/%s", hostname, webhook.getId().toString());

            // Create the body with support for push, pull request, and release events
            String body = "{\"description\":\"Terrakube\",\"url\":\"" + webhookUrl
                    + "\",\"active\":true,\"events\":[\"repo:push\",\"pullrequest:created\",\"pullrequest:updated\"],\"secret\":\"" + secret + "\"}";

            String apiUrl = workspace.getVcs().getApiUrl() + "/repositories/" + String.join("/", ownerAndRepo) + "/hooks";

            ResponseEntity<String> response = callBitBucketApi(workspace.getVcs().getAccessToken(), body, apiUrl,
                    HttpMethod.POST);

            // Extract the id from the response
            if (response.getStatusCode().value() == 201) {
                ObjectMapper objectMapper = new ObjectMapper();
                try {
                    JsonNode rootNode = objectMapper.readTree(response.getBody());
                    remoteHookId = rootNode.path("uuid").asText();
                } catch (Exception e) {
                    log.error("Error parsing JSON response", e);
                }

                log.info("Bitbucket Hook created successfully for workspace {}/{} with id {}",
                        workspace.getOrganization().getName(), workspace.getName(), remoteHookId);
            } else {
                log.error("Error creating the webhook" + response.getBody());
            }
        } else {
            log.info("webhook already created. Updating webhook with remote hook id {}", remoteHookId);
        }


        return remoteHookId;
    }

    public void deleteWebhook(Workspace workspace, String webhookRemoteId) {
        String apiUrl = webhookRemoteId;
        String ownerAndRepo = String.join("/", extractOwnerAndRepo(workspace.getSource()));

        if (!webhookRemoteId.substring(0, 4).equals("http")) {
            apiUrl = workspace.getVcs().getApiUrl() + "/repositories/" + ownerAndRepo + "/hooks/" + webhookRemoteId;
        }

        ResponseEntity<String> response = callBitBucketApi(workspace.getVcs().getAccessToken(), "", apiUrl,
                HttpMethod.DELETE);
        if (response.getStatusCode().value() == 204) {
            log.info("Webhook with remote hook id {} on repository {} deleted successfully", webhookRemoteId,
                    ownerAndRepo);
        } else {
            log.warn("Failed to delete webhook with remote hook id {} on repository {}, message {}", webhookRemoteId,
                    ownerAndRepo, response.getBody());
        }
    }

    private ResponseEntity<String> callBitBucketApi(String token, String body, String apiUrl, HttpMethod httpMethod) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("Authorization", "Bearer " + token);

        return makeApiRequest(headers, body, apiUrl, httpMethod);
    }
}