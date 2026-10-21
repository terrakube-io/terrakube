package io.terrakube.executor.service.terraform.spool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ContextSpoolServiceTest {

    private TerraformState terraformState;
    private ObjectMapper objectMapper;
    private ContextSpoolService service;

    @BeforeEach
    void setUp() {
        terraformState = mock(TerraformState.class);
        objectMapper = new ObjectMapper();
        service = new ContextSpoolService(terraformState, objectMapper);
    }

    @Test
    void spoolContextPatch_createsValidGzippedEnvelope() throws IOException {
        String orgId = "org-1";
        String jobId = "job-100";
        String stepId = "step-200";
        String phase = "plan";

        Map<String, Object> patch = new HashMap<>();
        patch.put("planStructuredOutput", Map.of("step-200", List.of(Map.of("address", "aws_instance.foo"))));

        when(terraformState.spoolContextPatch(eq(orgId), eq(jobId), eq(stepId), eq(phase), any(byte[].class)))
                .thenReturn("context-spool/org-1/job-100/step-200-plan.json.gz");

        String result = service.spoolContextPatch(orgId, jobId, stepId, phase, patch);

        assertEquals("context-spool/org-1/job-100/step-200-plan.json.gz", result);

        ArgumentCaptor<byte[]> bytesCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(terraformState).spoolContextPatch(eq(orgId), eq(jobId), eq(stepId), eq(phase), bytesCaptor.capture());

        byte[] gzippedBytes = bytesCaptor.getValue();
        assertNotNull(gzippedBytes);
        assertTrue(gzippedBytes.length > 0);

        Map<String, Object> payload = service.deserializePayload(gzippedBytes);
        assertNotNull(payload);
        assertTrue(payload.containsKey("envelope"));
        assertTrue(payload.containsKey("patch"));

        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) payload.get("envelope");
        assertEquals(orgId, envelope.get("organizationId"));
        assertEquals(jobId, envelope.get("jobId"));
        assertEquals(stepId, envelope.get("stepId"));
        assertEquals(phase, envelope.get("phase"));
        assertEquals(1, envelope.get("deliveryRevision"));
        assertNotNull(envelope.get("timestamp"));

        @SuppressWarnings("unchecked")
        Map<String, Object> deserializedPatch = (Map<String, Object>) payload.get("patch");
        assertTrue(deserializedPatch.containsKey("planStructuredOutput"));
    }

    @Test
    void spoolContextPatch_returnsNullWhenTerraformStateIsNull() {
        ContextSpoolService nullStateService = new ContextSpoolService(null, objectMapper);
        String result = nullStateService.spoolContextPatch("o", "j", "s", "plan", Map.of());
        assertNull(result);
    }

    @Test
    void spoolContextPatch_returnsNullWhenStorageFails() {
        when(terraformState.spoolContextPatch(any(), any(), any(), any(), any()))
                .thenThrow(new RuntimeException("Storage unreachable"));

        String result = service.spoolContextPatch("o", "j", "s", "plan", Map.of());
        assertNull(result);
    }

    @Test
    void gunzip_throwsIOExceptionWhenUncompressedExceedsLimit() throws IOException {
        byte[] uncompressed = "B".repeat(1000).getBytes();
        byte[] gzipped = ContextSpoolService.gzip(uncompressed);

        assertThrows(IOException.class, () -> ContextSpoolService.gunzip(gzipped, 50));
    }

    @Test
    void gunzip_decompressesValidStreamWithinLimit() throws IOException {
        byte[] uncompressed = "Hello Terrakube".getBytes();
        byte[] gzipped = ContextSpoolService.gzip(uncompressed);

        byte[] result = ContextSpoolService.gunzip(gzipped, 1024);
        assertArrayEquals(uncompressed, result);
    }
}
