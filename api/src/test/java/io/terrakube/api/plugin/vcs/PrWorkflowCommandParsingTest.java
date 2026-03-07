package io.terrakube.api.plugin.vcs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests command parsing logic in WebhookServiceBase.
 * Both parseTerrakubeCommand and escapeJsonString are shared utilities
 * inherited by all VCS providers.
 */
@ExtendWith(MockitoExtension.class)
public class PrWorkflowCommandParsingTest {

    // Concrete subclass to access protected methods from WebhookServiceBase
    private static class TestableWebhookService extends WebhookServiceBase {}

    private final TestableWebhookService service = new TestableWebhookService();

    @Test
    public void parsePlanCommand() {
        assertEquals("plan", service.parseTerrakubeCommand("terrakube plan"));
    }

    @Test
    public void parseApplyCommand() {
        assertEquals("apply", service.parseTerrakubeCommand("terrakube apply"));
    }

    @Test
    public void parsePlanCommandWithTrailingSpace() {
        assertEquals("plan", service.parseTerrakubeCommand("terrakube plan "));
    }

    @Test
    public void parseApplyCommandWithArgs() {
        assertEquals("apply", service.parseTerrakubeCommand("terrakube apply -auto-approve"));
    }

    @Test
    public void parsePlanCommandCaseInsensitive() {
        assertEquals("plan", service.parseTerrakubeCommand("Terrakube Plan"));
    }

    @Test
    public void parseApplyCommandCaseInsensitive() {
        assertEquals("apply", service.parseTerrakubeCommand("TERRAKUBE APPLY"));
    }

    @Test
    public void parseUnknownCommandReturnsNull() {
        assertNull(service.parseTerrakubeCommand("terrakube destroy"));
    }

    @Test
    public void parseRandomTextReturnsNull() {
        assertNull(service.parseTerrakubeCommand("looks good to me"));
    }

    @Test
    public void parseEmptyStringReturnsNull() {
        assertNull(service.parseTerrakubeCommand(""));
    }

    @Test
    public void parseNullReturnsNull() {
        assertNull(service.parseTerrakubeCommand(null));
    }

    @Test
    public void parseCommandWithLeadingWhitespace() {
        assertEquals("plan", service.parseTerrakubeCommand("  terrakube plan"));
    }

    @Test
    public void parsePartialCommandReturnsNull() {
        assertNull(service.parseTerrakubeCommand("terrakube"));
    }

    @Test
    public void escapeJsonStringHandlesSpecialChars() {
        String input = "line1\nline2\ttab\"quote\\backslash";
        String escaped = service.escapeJsonString(input);
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
