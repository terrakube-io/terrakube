package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerraformOutputsServiceTest {

    private TerraformOutputsService subject() {
        return new TerraformOutputsService(
                Mockito.mock(JobContextService.class),
                new ObjectMapper());
    }

    @Test
    void buildsOutputsSortedByNameAndRedactsSensitiveValues() throws Exception {
        String outputJson = """
                {
                  "random_value": {"value": "sad-otter", "type": "string", "sensitive": false},
                  "random_password_result": {"value": "top-secret", "type": "string", "sensitive": true}
                }
                """;

        List<Map<String, Object>> outputs = subject().buildOutputsFromJson(outputJson);

        assertEquals(2, outputs.size());
        assertEquals("random_password_result", outputs.get(0).get("name"));
        assertEquals(true, outputs.get(0).get("sensitive"));
        assertNull(outputs.get(0).get("value"));
        assertEquals("random_value", outputs.get(1).get("name"));
        assertEquals(false, outputs.get(1).get("sensitive"));
        assertEquals("sad-otter", outputs.get(1).get("value"));
    }

    @Test
    void returnsEmptyListForBlankOrMissingOutputJson() throws Exception {
        assertTrue(subject().buildOutputsFromJson("").isEmpty());
        assertTrue(subject().buildOutputsFromJson(null).isEmpty());
    }

    @Test
    void updateOutputsContextMergesWithoutDroppingExistingKeys() {
        Map<String, Object> context = new HashMap<>();
        context.put("custom", "value");
        context.put("terraformOutputs", Map.of("existing-step", List.of(Map.of("name", "existing"))));

        Map<String, Object> updated = subject().updateOutputsContext(
                context, "new-step", List.of(Map.of("name", "random_value", "value", "sad-otter", "sensitive", false)));

        assertEquals("value", updated.get("custom"));
        Map<String, Object> terraformOutputs = (Map<String, Object>) updated.get("terraformOutputs");
        assertTrue(terraformOutputs.containsKey("existing-step"));
        assertTrue(terraformOutputs.containsKey("new-step"));
    }
}
