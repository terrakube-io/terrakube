package io.terrakube.api.plugin.token.login;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConsentPageRendererTest {

    private final ConsentPageRenderer r = new ConsentPageRenderer();

    @Test
    void consentPageHasFormAndFilteredOptions() {
        String html = r.renderConsent("alice@example.io", 30, 60, null);
        assertTrue(html.contains("<form method=\"post\" action=\"/oauth/consent\""));
        assertTrue(html.contains("alice@example.io"));
        assertTrue(html.contains("value=\"30\" selected"));
        assertTrue(html.contains("value=\"60\""));
        assertFalse(html.contains("value=\"180\""));
        assertFalse(html.contains("value=\"90\""));   // filtered out by maxDays=60
        assertTrue(html.contains("name=\"decision\" value=\"authorize\""));
        assertTrue(html.contains("name=\"decision\" value=\"deny\""));
    }

    @Test
    void consentPageOffersTheMaximumSupportedDuration() {
        String html = r.renderConsent("alice@example.io", 30, 365, null);
        assertTrue(html.contains("value=\"180\""));
        assertTrue(html.contains("value=\"365\""));
    }

    @Test
    void consentPageIsBrandedWithAnOptionalNameField() {
        String html = r.renderConsent("alice@example.io", 30, 90, null);
        assertTrue(html.contains("TERRAKUBE"));               // inline logo wordmark
        assertTrue(html.contains("prefers-color-scheme:dark")); // theme-aware
        assertTrue(html.contains("name=\"name\""));            // optional token-name input
        assertTrue(html.contains("id=\"days\""));
    }

    @Test
    void escapesEmailAndError() {
        String html = r.renderConsent("<script>x</script>@e.io", 30, 90, "bad & wrong");
        assertFalse(html.contains("<script>x</script>"));
        assertTrue(html.contains("&lt;script&gt;"));
        assertTrue(html.contains("bad &amp; wrong"));
    }
}
