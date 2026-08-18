package io.terrakube.api.plugin.notification.controller;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.terrakube.api.plugin.notification.NotificationTestService;
import io.terrakube.api.plugin.notification.sender.NotificationDeliveryException;
import io.terrakube.api.repository.NotificationConfigurationRepository;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.notification.NotificationConfiguration;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/notification/v1")
public class NotificationTestController {

    private final NotificationConfigurationRepository notificationConfigurationRepository;
    private final OrganizationRepository organizationRepository;
    private final NotificationTestService notificationTestService;
    private final TransactionTemplate readOnlyTransactionTemplate;

    public NotificationTestController(NotificationConfigurationRepository notificationConfigurationRepository,
            OrganizationRepository organizationRepository, NotificationTestService notificationTestService,
            PlatformTransactionManager transactionManager) {
        this.notificationConfigurationRepository = notificationConfigurationRepository;
        this.organizationRepository = organizationRepository;
        this.notificationTestService = notificationTestService;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    @PostMapping("/configuration/{configId}/test")
    @PreAuthorize("@notificationConfigurationAccessService.hasManagePermission(authentication, #configId)")
    public ResponseEntity<Void> sendTest(@PathVariable String configId) {
        // Fetch and force-initialize the lazy organization proxy inside a short, dedicated
        // transaction that closes before the outbound HTTP call below - keeping a transaction
        // (and its pooled DB connection) open across a real network call to an external
        // destination is exactly the anti-pattern the outbox's claim/deliver/record-result split
        // was built to avoid for real deliveries; a burst of concurrent "Send Test" clicks against
        // a slow/hanging destination shouldn't be able to exhaust the connection pool.
        NotificationConfiguration configuration = readOnlyTransactionTemplate.execute(status -> {
            NotificationConfiguration found = notificationConfigurationRepository
                    .findById(UUID.fromString(configId)).orElse(null);
            if (found != null) {
                found.getOrganization().getName();
            }
            return found;
        });
        if (configuration == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            notificationTestService.sendTest(configuration);
            return ResponseEntity.ok().build();
        } catch (NotificationDeliveryException e) {
            log.warn("Test notification failed for configuration {}: {}", configId, e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }

    // Lets the create form verify a destination before the configuration has ever
    // been saved: same rendering/delivery path as the saved-config test above, just
    // against a NotificationConfiguration that's built in memory and never persisted.
    @PostMapping("/organization/{orgId}/configuration/test")
    @PreAuthorize("@notificationConfigurationAccessService.hasManagePermissionForOrganization(authentication, #orgId)")
    public ResponseEntity<Void> sendAdHocTest(@PathVariable String orgId,
            @RequestBody NotificationAdHocTestRequest request) {
        Organization organization = organizationRepository.findById(UUID.fromString(orgId)).orElse(null);
        if (organization == null) {
            return ResponseEntity.notFound().build();
        }
        NotificationConfiguration configuration = new NotificationConfiguration();
        configuration.setOrganization(organization);
        configuration.setChannelType(request.channelType());
        configuration.setDestinationUrl(request.destinationUrl());
        configuration.setSigningSecret(request.signingSecret());
        try {
            notificationTestService.sendTest(configuration);
            return ResponseEntity.ok().build();
        } catch (NotificationDeliveryException e) {
            log.warn("Ad-hoc test notification failed for organization {}: {}", orgId, e.getMessage());
            return ResponseEntity.status(502).build();
        }
    }
}
