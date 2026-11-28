package io.terrakube.executor.service.infracost;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class InfracostEnvSanitizerTest {

    @Test
    void testSanitizeForTerraformUnsetsSensitiveKeysWithoutMutatingSource() {
        Map<String, String> source = new HashMap<>();
        source.put("INFRACOST_API_KEY", "ico-secret-123456");
        source.put("infracost_api_key", "ico-lower");
        source.put("INFRACOST_PRICING_API_ENDPOINT", "https://evil.example");
        source.put("INFRACOST_ENABLE_DASHBOARD", "true");
        source.put("TF_VAR_env", "prod");

        HashMap<String, String> sanitized = InfracostEnvSanitizer.sanitizeForTerraform(source);

        assertEquals("prod", sanitized.get("TF_VAR_env"));
        assertTrue(sanitized.containsKey("INFRACOST_API_KEY"));
        assertNull(sanitized.get("INFRACOST_API_KEY"));
        assertTrue(sanitized.containsKey("infracost_api_key"));
        assertNull(sanitized.get("infracost_api_key"));
        assertTrue(sanitized.containsKey("INFRACOST_PRICING_API_ENDPOINT"));
        assertNull(sanitized.get("INFRACOST_PRICING_API_ENDPOINT"));
        assertTrue(sanitized.containsKey("INFRACOST_ENABLE_DASHBOARD"));
        assertNull(sanitized.get("INFRACOST_ENABLE_DASHBOARD"));

        // Source map stays untouched so InfracostExecutorServiceImpl still reads INFRACOST_API_KEY
        assertEquals("ico-secret-123456", source.get("INFRACOST_API_KEY"));
        assertEquals("https://evil.example", source.get("INFRACOST_PRICING_API_ENDPOINT"));
        assertEquals("true", source.get("INFRACOST_ENABLE_DASHBOARD"));
    }

    @Test
    void testSanitizeForTerraformAlsoUnsetsSystemEnvKeys() {
        Map<String, String> source = Map.of("TF_VAR_env", "staging");
        HashMap<String, String> sanitized = InfracostEnvSanitizer.sanitizeForTerraform(
                source,
                java.util.Set.of("PATH", "INFRACOST_API_KEY", "INFRACOST_PRICING_API_ENDPOINT", "INFRACOST_ENABLE_DASHBOARD")
        );

        assertEquals("staging", sanitized.get("TF_VAR_env"));
        assertTrue(sanitized.containsKey("INFRACOST_API_KEY"));
        assertNull(sanitized.get("INFRACOST_API_KEY"));
        assertTrue(sanitized.containsKey("INFRACOST_PRICING_API_ENDPOINT"));
        assertNull(sanitized.get("INFRACOST_PRICING_API_ENDPOINT"));
        assertTrue(sanitized.containsKey("INFRACOST_ENABLE_DASHBOARD"));
        assertNull(sanitized.get("INFRACOST_ENABLE_DASHBOARD"));
    }

    @Test
    void testStripFromEnvironmentMapRemovesCaseVariants() {
        Map<String, String> env = new HashMap<>();
        env.put("INFRACOST_API_KEY", "ico-secret");
        env.put("infracost_pricing_api_endpoint", "https://pricing.example");
        env.put("Infracost_Enable_Dashboard", "true");
        env.put("PATH", "/usr/bin");

        InfracostEnvSanitizer.stripFromEnvironmentMap(env);

        assertFalse(env.containsKey("INFRACOST_API_KEY"));
        assertFalse(env.containsKey("infracost_pricing_api_endpoint"));
        assertFalse(env.containsKey("Infracost_Enable_Dashboard"));
        assertEquals("/usr/bin", env.get("PATH"));
    }
}
