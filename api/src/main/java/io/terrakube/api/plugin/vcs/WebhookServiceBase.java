package io.terrakube.api.plugin.vcs;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import io.terrakube.api.rs.job.JobStatus;

import org.apache.commons.lang3.function.TriFunction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import io.micrometer.core.instrument.Counter;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class WebhookServiceBase {

    // Field injection, not the constructor injection used elsewhere in this codebase: every
    // GitHub/GitLab/Azure DevOps/Bitbucket webhook service subclasses this with its own
    // constructor that doesn't call super(...) explicitly, relying on the implicit no-arg
    // constructor - adding a constructor parameter here would force updating all four subclasses'
    // constructors (and every caller that builds them) just to thread through a dependency none of
    // them otherwise need direct access to. @Setter (public) exists purely so
    // GitHubWebhookServiceTest can inject a WireMock-backed RestTemplate in a plain unit test,
    // outside of Spring.
    @Autowired
    @Qualifier("webhookRestTemplate")
    @Setter
    private RestTemplate webhookRestTemplate;

    @Autowired
    private Counter webhookTimeoutCounter;

    protected String[] extractOwnerAndRepo(String repoUrl) {
        try {
            URI uri = new URI(repoUrl);
            return Arrays.copyOfRange(uri.getPath().replaceAll("\\.git$", "").split("/"), 1, 3);
        } catch (Exception e) {
            log.error("error extracting the repo", e);
            return null;
        }
    }

    /*
    GitLab is a special case, the repos URL could be
    https://gitlab.com/myuser/simple-terraform -> Normal repo
    https://gitlab.com/terraform2745926/simple-terraform -> Repo inside project
    https://gitlab.com/terraform2745926/test/simple-terraform -> Repo inside project and subgroup
     */
    protected String extractOwnerAndRepoGitlab(String repoUrl) {
        try {
            URI uri = new URI(repoUrl);
            return uri.getPath().replaceAll("\\.git$", "").substring(1);
        } catch (Exception e) {
            log.error("error extracting the gitlab repo", e);
            return null;
        }
    }

    protected static String bytesToHex(byte[] hash) {
        StringBuilder hexString = new StringBuilder(2 * hash.length);
        for (byte b : hash) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }
        return hexString.toString();
    }

    protected boolean verifySignature(Map<String, String> headers, String headerName, String token,
            String jsonPayload) {
        try {
            String signatureHeader = headers.get(headerName);
            if (signatureHeader == null) {
                log.error(headerName + " header is missing!");
                return false;
            }
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKeySpec = new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(secretKeySpec);
            byte[] computedHash = mac.doFinal(jsonPayload.getBytes(StandardCharsets.UTF_8));
            String expectedSignature = "sha256=" + bytesToHex(computedHash);

            if (!MessageDigest.isEqual(
                    signatureHeader.getBytes(StandardCharsets.UTF_8),
                    expectedSignature.getBytes(StandardCharsets.UTF_8))) {
                log.error("Request signature didn't match!");
                return false;
            }
            return true;
        } catch (NoSuchAlgorithmException e) {
            log.info("Error processing the webhook", e);
            return false;
        } catch (InvalidKeyException e) {
            log.info("Error parsing the secret", e);
            return false;
        }

    }

    protected ResponseEntity<String> makeApiRequest(HttpHeaders headers, String body, String apiUrl) {
        return makeApiRequest(headers, body, apiUrl, HttpMethod.POST);
    }

    protected ResponseEntity<String> makeApiRequest(HttpHeaders headers, String body, String apiUrl, HttpMethod method) {
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        try {
            return webhookRestTemplate.exchange(apiUrl, method, entity, String.class);
        } catch (org.springframework.web.client.ResourceAccessException e) {
            // RestTemplate wraps every low-level I/O failure from the underlying HTTP client
            // (connect timeout, response timeout, connection-pool-acquisition timeout, DNS
            // failure, connection reset) in this one exception type - counted together as
            // "timeout" per the spec's wording, re-thrown unchanged so every existing caller's
            // error handling (e.g. GitHubWebhookService.sendCommitStatus is already called from
            // inside ScheduleJob.updateJobStatusOnVcs's catch-all) is unaffected.
            webhookTimeoutCounter.increment();
            throw e;
        }
    }

    protected String parseTerrakubeCommand(String commentBody) {
        if (commentBody == null) return null;
        String lower = commentBody.trim().toLowerCase();
        if (lower.equals("terrakube plan") || lower.startsWith("terrakube plan ")) return "plan";
        if (lower.equals("terrakube apply") || lower.startsWith("terrakube apply ")) return "apply";
        return null;
    }

    public static String buildCommitStatusDescription(JobStatus jobStatus, String runSummary) {
        String description;
        switch (jobStatus) {
            case completed:
                description = "Your task has been completed successfully.";
                break;
            case failed:
            case rejected:
            case cancelled:
                description = "Your task has failed.";
                break;
            case unknown:
                description = "Your task ran into errors.";
                break;
            default:
                description = "Your task is in Terrakube queue.";
                break;
        }
        if (runSummary != null && !runSummary.isBlank()) {
            description = description + " " + runSummary;
        }
        return description;
    }

    protected String escapeJsonString(String input) {
        return input.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    protected WebhookResult handleWebhook(String jsonPayload, Map<String, String> headers, String token,
            String signatureHeader, String via,
            TriFunction<String, WebhookResult, Map<String, String>, WebhookResult> handleEvent) {
        WebhookResult result = new WebhookResult();
        result.setBranch("");
        result.setVia(via);
        String workspaceId = new String(Base64.getMimeDecoder().decode(token.getBytes(StandardCharsets.UTF_8)));
        log.info("WorkspaceId: {}", workspaceId);
        result.setWorkspaceId(workspaceId);

        log.info("verify signature for " + via + " webhook");
        result.setValid(verifySignature(headers, signatureHeader, token, jsonPayload));

        if (!result.isValid()) {
            log.info("Signature verification failed");
            return result;
        }

        log.info("Parsing " + via + " webhook payload");

        try {
            result = handleEvent.apply(jsonPayload, result, headers);
        } catch (Exception e) {
            log.info("Error processing the webhook", e);
        }

        return result;
    }

}
