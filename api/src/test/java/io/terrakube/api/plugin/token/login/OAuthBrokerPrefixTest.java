package io.terrakube.api.plugin.token.login;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The CLI login broker works under the single-domain /api prefix and is unchanged without it. */
class OAuthBrokerPrefixTest {

    private static final String SECRET = "ejZRSFgheUBOZXAyUURUITUzdmdINDNeUGpSWHlDM1g=";

    private static TerraformLoginProperties props(String apiUrl, String publicUrl) {
        TerraformLoginProperties props = new TerraformLoginProperties();
        props.setEnabled(true);
        props.setApiUrl(apiUrl);
        props.setPublicUrl(publicUrl);
        props.normalize();
        return props;
    }

    @Test
    void splitDomainKeepsOauthPath() {
        TerraformLoginProperties props = props("https://terrakube-api.example.com", "");
        assertEquals("/oauth", props.oauthPath());
        assertEquals("https://terrakube-api.example.com/oauth/callback", props.getCallbackUrl());
        assertEquals("/oauth", new CliLoginCookie(SECRET, props).build("sid").getPath());
        assertTrue(new ConsentPageRenderer(props).renderConsent("a@example.io", 30, 60, null)
                .contains("action=\"/oauth/consent\""));
    }

    @Test
    void singleDomainDerivesPrefixedBroker() {
        TerraformLoginProperties props = props(null, "https://app.example.com");
        assertEquals("https://app.example.com/api", props.getApiUrl());
        assertEquals("/api/oauth", props.oauthPath());
        assertEquals("https://app.example.com/api/oauth/callback", props.getCallbackUrl());
        assertEquals("/api/oauth", new CliLoginCookie(SECRET, props).build("sid").getPath());
        assertTrue(new ConsentPageRenderer(props).renderConsent("a@example.io", 30, 60, null)
                .contains("action=\"/api/oauth/consent\""));
    }

    @Test
    void brokerDisabledWithoutUrlFallsBackToOauthPath() {
        TerraformLoginProperties props = new TerraformLoginProperties();
        props.normalize();
        assertEquals("/oauth", props.oauthPath());
    }
}
