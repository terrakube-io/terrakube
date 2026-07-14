package io.terrakube.api.plugin.vcs.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.plugin.vcs.WebhookService;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@RestController
public class WebHookController {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    @Autowired
    WebhookService webhookService;

    @Autowired
    RepoWebhookService repoWebhookService;

    @Autowired
    ObjectMapper objectMapper;

    @PostMapping("/webhook/v1/{webhookId}")
    public ResponseEntity<String> processWebhook(@PathVariable String webhookId,@RequestBody Map<String, Object> payload,@RequestHeader Map<String, String> headers) {

        log.info("Processing webhook {}", webhookId);
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            webhookService.processWebhook(webhookId, jsonPayload,headers);
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/webhook/v2/{repoWebhookId}")
    public ResponseEntity<String> processV2Webhook(@PathVariable String repoWebhookId, @RequestBody Map<String, Object> payload, @RequestHeader Map<String, String> headers) {
        if (!UUID_PATTERN.matcher(repoWebhookId).matches()) {
            return ResponseEntity.status(401).build();
        }
        log.info("Processing v2 webhook");
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            repoWebhookService.processV2Webhook(repoWebhookId, jsonPayload, headers);
        } catch (IllegalArgumentException | SecurityException e) {
            log.warn("V2 webhook request rejected");
            return ResponseEntity.status(401).build();
        } catch (Exception e) {
            log.error("Error processing v2 webhook", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }
}