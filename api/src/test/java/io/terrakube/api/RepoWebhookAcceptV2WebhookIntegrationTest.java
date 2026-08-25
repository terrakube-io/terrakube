package io.terrakube.api;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.terrakube.api.plugin.vcs.RepoWebhookService;
import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.repository.RepoWebhookRepository;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.vcs.VcsType;
import io.terrakube.api.rs.webhook.RepoWebhook;

import static org.assertj.core.api.Assertions.assertThat;

// Deliberately NOT @Transactional at the class level: WebHookController calls
// RepoWebhookService.acceptV2Webhook() with no ambient transaction of its own, and that method's
// own @Transactional is what's under test here. Wrapping this test in @Transactional would keep
// one Hibernate session open across the whole test method and mask exactly the
// LazyInitializationException this test exists to catch - repoWebhook.getVcs() is a proxy loaded
// by a repository call (findById) whose own implicit per-call transaction closes before
// acceptV2Webhook's method body reads it, unless acceptV2Webhook has its own @Transactional.
class RepoWebhookAcceptV2WebhookIntegrationTest extends ServerApplicationTests {

    @Autowired
    RepoWebhookService repoWebhookService;

    @Autowired
    RepoWebhookRepository repoWebhookRepository;

    @Autowired
    VcsRepository vcsRepository;

    @Autowired
    OrganizationRepository organizationRepository;

    @Test
    void acceptV2WebhookDoesNotThrowLazyInitializationExceptionLoadingVcs() throws Exception {
        Organization organization = organizationRepository
                .findById(UUID.fromString("d9b58bd3-f3fc-4056-a026-1163297e80a8")).orElseThrow();

        Vcs vcs = new Vcs();
        vcs.setName("regression-test-vcs");
        vcs.setDescription("Regression test VCS connection");
        vcs.setVcsType(VcsType.GITHUB);
        vcs.setOrganization(organization);
        vcs = vcsRepository.saveAndFlush(vcs);

        String secret = "regression-test-secret";
        RepoWebhook repoWebhook = new RepoWebhook();
        repoWebhook.setRepositoryUrl("https://github.com/owner/lazy-init-regression-" + UUID.randomUUID());
        repoWebhook.setWebhookSecret(secret);
        repoWebhook.setVcs(vcs);
        repoWebhook = repoWebhookRepository.saveAndFlush(repoWebhook);

        String payload = "{\"zen\":\"test\"}";
        Map<String, String> headers = Map.of(
                "x-hub-signature-256", computeHmac(secret, payload),
                "x-github-event", "ping");

        // Regression test for the bug that broke every v2 webhook delivery in production:
        // acceptV2Webhook loads repoWebhook via a fresh repository call (this test has no ambient
        // transaction, matching WebHookController), then immediately reads
        // repoWebhook.getVcs().getVcsType() to pick a provider (isGitLab/isAzureDevOps) before it
        // even gets to signature verification - if acceptV2Webhook isn't itself @Transactional,
        // that lazy proxy has no session left to load through and throws
        // org.hibernate.LazyInitializationException.
        UUID deliveryId = repoWebhookService.acceptV2Webhook(repoWebhook.getId().toString(), payload, headers);

        assertThat(deliveryId).isNotNull();
    }

    private String computeHmac(String secret, String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(keySpec);
        byte[] hash = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String h = Integer.toHexString(0xff & b);
            if (h.length() == 1) {
                hex.append('0');
            }
            hex.append(h);
        }
        return "sha256=" + hex;
    }
}
