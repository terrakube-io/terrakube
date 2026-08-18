package io.terrakube.executor.service.terraform;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformSensitivitySanitizerTest {

    @Test
    void sanitizeSensitiveValuesRedactsTrueMetadataLeaves() {
        Map<String, Object> value = Map.of(
                "public_key", "ssh-rsa AAAAB3...",
                "secret_key", "super-secret",
                "nested", Map.of("sub_secret", "secret-123", "sub_public", "public-123"),
                "list_field", List.of("item1", "item2")
        );

        Map<String, Object> sensitiveMetadata = Map.of(
                "secret_key", true,
                "nested", Map.of("sub_secret", true),
                "list_field", List.of(false, true)
        );

        Object sanitizedRaw = TerraformSensitivitySanitizer.sanitizeSensitiveValues(value, sensitiveMetadata);
        assertTrue(sanitizedRaw instanceof Map<?, ?>);
        Map<?, ?> sanitized = (Map<?, ?>) sanitizedRaw;

        assertEquals("ssh-rsa AAAAB3...", sanitized.get("public_key"));
        assertNull(sanitized.get("secret_key"));

        Map<?, ?> nested = (Map<?, ?>) sanitized.get("nested");
        assertNull(nested.get("sub_secret"));
        assertEquals("public-123", nested.get("sub_public"));

        List<?> listField = (List<?>) sanitized.get("list_field");
        assertEquals("item1", listField.get(0));
        assertNull(listField.get(1));
    }

    @Test
    void sanitizeSensitiveValuesRedactsEntireNodeWhenMetadataIsTrue() {
        assertNull(TerraformSensitivitySanitizer.sanitizeSensitiveValues("secret-string", true));
        assertNull(TerraformSensitivitySanitizer.sanitizeSensitiveValues(Map.of("a", 1), true));
        assertNull(TerraformSensitivitySanitizer.sanitizeSensitiveValues(List.of(1, 2), true));
    }

    @Test
    void sanitizeSensitiveValuesReturnsUnmodifiedWhenNotSensitive() {
        assertEquals("plain", TerraformSensitivitySanitizer.sanitizeSensitiveValues("plain", false));
        assertEquals("plain", TerraformSensitivitySanitizer.sanitizeSensitiveValues("plain", null));
        assertEquals(123, TerraformSensitivitySanitizer.sanitizeSensitiveValues(123, null));
    }

    @Test
    void normalizeResourceSensitivitiesPropagatesInputToOutputForTerraformData() {
        Map<String, Object> sensitiveMap = new HashMap<>();
        sensitiveMap.put("input", true);
        sensitiveMap.put("output", new HashMap<>());

        Object normalized = TerraformSensitivitySanitizer.normalizeResourceSensitivities(
                "terraform_data", "terraform_data.example", sensitiveMap);

        assertTrue(normalized instanceof Map<?, ?>);
        Map<?, ?> result = (Map<?, ?>) normalized;
        assertEquals(true, result.get("output"));
        assertEquals(true, result.get("input"));
    }

    @Test
    void normalizeResourceSensitivitiesPropagatesInputToOutputWhenAddressContainsTerraformData() {
        Map<String, Object> sensitiveMap = new HashMap<>();
        sensitiveMap.put("input", true);

        Map<String, Object> change = Map.of("address", "module.sub.terraform_data.foo");
        Object normalized = TerraformSensitivitySanitizer.normalizeResourceSensitivities(change, sensitiveMap);

        assertTrue(normalized instanceof Map<?, ?>);
        Map<?, ?> result = (Map<?, ?>) normalized;
        assertEquals(true, result.get("output"));
    }

    @Test
    void normalizeResourceSensitivitiesDoesNotOverrideExistingOutputSensitivity() {
        Map<String, Object> sensitiveMap = new HashMap<>();
        sensitiveMap.put("input", true);
        sensitiveMap.put("output", Map.of("nested", true));

        Object normalized = TerraformSensitivitySanitizer.normalizeResourceSensitivities(
                "terraform_data", "terraform_data.example", sensitiveMap);

        Map<?, ?> result = (Map<?, ?>) normalized;
        assertEquals(Map.of("nested", true), result.get("output"));
    }

    @Test
    void normalizeResourceSensitivitiesIgnoresNonTerraformDataResources() {
        Map<String, Object> sensitiveMap = new HashMap<>();
        sensitiveMap.put("input", true);

        Object normalized = TerraformSensitivitySanitizer.normalizeResourceSensitivities(
                "aws_instance", "aws_instance.web", sensitiveMap);

        Map<?, ?> result = (Map<?, ?>) normalized;
        assertNull(result.get("output"));
    }

    @Test
    void mergeSensitiveMetadataCombinesPlanAndStateSensitivity() {
        Map<String, Object> planSensitive = Map.of(
                "field_a", true,
                "nested", Map.of("secret_a", true)
        );
        Map<String, Object> stateSensitive = Map.of(
                "field_b", true,
                "nested", Map.of("secret_b", true)
        );

        Object mergedRaw = TerraformSensitivitySanitizer.mergeSensitiveMetadata(planSensitive, stateSensitive);
        assertTrue(mergedRaw instanceof Map<?, ?>);
        Map<?, ?> merged = (Map<?, ?>) mergedRaw;

        assertEquals(true, merged.get("field_a"));
        assertEquals(true, merged.get("field_b"));

        Map<?, ?> nested = (Map<?, ?>) merged.get("nested");
        assertEquals(true, nested.get("secret_a"));
        assertEquals(true, nested.get("secret_b"));
    }

    @Test
    void mergeSensitiveMetadataHandlesLists() {
        List<Object> planList = List.of(true, false);
        List<Object> stateList = List.of(false, true, true);

        Object mergedRaw = TerraformSensitivitySanitizer.mergeSensitiveMetadata(planList, stateList);
        assertTrue(mergedRaw instanceof List<?>);
        List<?> merged = (List<?>) mergedRaw;

        assertEquals(3, merged.size());
        assertEquals(true, merged.get(0));
        assertEquals(true, merged.get(1));
        assertEquals(true, merged.get(2));
    }
}
