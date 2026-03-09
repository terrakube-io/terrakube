package io.terrakube.api.plugin.vcs.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import io.terrakube.api.plugin.vcs.WebhookService;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;

@Slf4j
@RestController
public class WebHookController {

    @Autowired
    WebhookService webhookService;

    @Autowired
    ObjectMapper objectMapper;

    @PostMapping("/webhook/v1/{webhookId}")
    public ResponseEntity<String> processWebhook(@PathVariable String webhookId,@RequestBody Map<String, Object> payload,@RequestHeader Map<String, String> headers) {

        log.info("Processing webhook {}", webhookId);
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            log.info("webhook payload: {}", jsonPayload);
            webhookService.processWebhook(webhookId, jsonPayload,headers);
        } catch (Exception e) {
            log.error("Error processing webhook", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/webhook/v2/{repoWebhookId}")
    public ResponseEntity<String> processVcsWebhook(@PathVariable String repoWebhookId, @RequestBody Map<String, Object> payload, @RequestHeader Map<String, String> headers) {

        log.info("Processing shared repository webhook {}", repoWebhookId);
        try {
            String jsonPayload = objectMapper.writeValueAsString(payload);
            log.info("shared webhook payload: {}", jsonPayload);
            webhookService.processSharedWebhook(repoWebhookId, jsonPayload, headers);
        } catch (Exception e) {
            log.error("Error processing shared VCS webhook", e);
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.ok().build();
    }

}