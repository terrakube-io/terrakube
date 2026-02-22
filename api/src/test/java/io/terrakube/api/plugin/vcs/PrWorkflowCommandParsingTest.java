package io.terrakube.api.plugin.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import io.terrakube.api.plugin.vcs.provider.github.GitHubWebhookService;
import io.terrakube.api.plugin.vcs.provider.gitlab.GitLabWebhookService;
import io.terrakube.api.plugin.vcs.provider.bitbucket.BitBucketWebhookService;

import com.fasterxml.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests command parsing logic across all VCS providers.
 * Uses reflection to test private parseTerrakubeCommand methods
 * since the logic is identical across providers.
 */
@ExtendWith(MockitoExtension.class)
public class PrWorkflowCommandParsingTest {

    private String invokeParseCommand(Object service, String commentBody) throws Exception {
        Method method = service.getClass().getDeclaredMethod("parseTerrakubeCommand", String.class);
        method.setAccessible(true);
        return (String) method.invoke(service, commentBody);
    }

    private GitHubWebhookService createGitHubService() {
        return new GitHubWebhookService(new ObjectMapper(), null);
    }

    @Test
    public void parsePlanCommand() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("plan", invokeParseCommand(service, "terrakube plan"));
    }

    @Test
    public void parseApplyCommand() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("apply", invokeParseCommand(service, "terrakube apply"));
    }

    @Test
    public void parsePlanCommandWithTrailingSpace() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("plan", invokeParseCommand(service, "terrakube plan "));
    }

    @Test
    public void parseApplyCommandWithArgs() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("apply", invokeParseCommand(service, "terrakube apply -auto-approve"));
    }

    @Test
    public void parsePlanCommandCaseInsensitive() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("plan", invokeParseCommand(service, "Terrakube Plan"));
    }

    @Test
    public void parseApplyCommandCaseInsensitive() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("apply", invokeParseCommand(service, "TERRAKUBE APPLY"));
    }

    @Test
    public void parseUnknownCommandReturnsNull() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertNull(invokeParseCommand(service, "terrakube destroy"));
    }

    @Test
    public void parseRandomTextReturnsNull() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertNull(invokeParseCommand(service, "looks good to me"));
    }

    @Test
    public void parseEmptyStringReturnsNull() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertNull(invokeParseCommand(service, ""));
    }

    @Test
    public void parseNullReturnsNull() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertNull(invokeParseCommand(service, null));
    }

    @Test
    public void parseCommandWithLeadingWhitespace() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertEquals("plan", invokeParseCommand(service, "  terrakube plan"));
    }

    @Test
    public void parsePartialCommandReturnsNull() throws Exception {
        GitHubWebhookService service = createGitHubService();
        assertNull(invokeParseCommand(service, "terrakube"));
    }

    @Test
    public void escapeJsonStringHandlesSpecialChars() throws Exception {
        GitHubWebhookService service = createGitHubService();
        Method method = service.getClass().getDeclaredMethod("escapeJsonString", String.class);
        method.setAccessible(true);

        String input = "line1\nline2\ttab\"quote\\backslash";
        String escaped = (String) method.invoke(service, input);

        assertEquals("line1\\nline2\\ttab\\\"quote\\\\backslash", escaped);
    }

    @Test
    public void webhookResultEventNormalizationForPrComment() {
        WebhookResult result = new WebhookResult();

        result.setEvent("issue_comment");
        assertEquals("pr_comment", result.getNormalizedEvent());

        result.setEvent("note");
        assertEquals("pr_comment", result.getNormalizedEvent());
    }
}
