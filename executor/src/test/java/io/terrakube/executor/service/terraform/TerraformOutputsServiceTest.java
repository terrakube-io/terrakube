package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.client.TerrakubeClient;
import io.terrakube.executor.service.workspace.security.WorkspaceSecurity;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
    @SuppressWarnings("unchecked")
    void publishOutputsPatchesOnlyThisStepsEntryWithoutReading() {
        JobContextService jobContextService = Mockito.mock(JobContextService.class);
        TerraformOutputsService service = new TerraformOutputsService(jobContextService, new ObjectMapper());

        service.publishOutputs("o", "1", "step-1", "{\"random_value\": {\"value\": \"sad-otter\", \"type\": \"string\"}}");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        Mockito.verify(jobContextService).patchContextFresh(Mockito.eq("o"), Mockito.eq("1"), captor.capture());
        Mockito.verify(jobContextService, Mockito.never()).getCurrentContext(Mockito.any(), Mockito.any());
        assertEquals(Set.of("terraformOutputs/step-1"), captor.getValue().keySet());
        List<Map<String, Object>> outputs = (List<Map<String, Object>>) captor.getValue().get("terraformOutputs/step-1");
        assertEquals("sad-otter", outputs.get(0).get("value"));
    }
}
