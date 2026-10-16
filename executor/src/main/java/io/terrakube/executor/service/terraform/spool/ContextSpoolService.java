package io.terrakube.executor.service.terraform.spool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.executor.plugin.tfstate.TerraformState;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

@Slf4j
@Service
public class ContextSpoolService {

    private final TerraformState terraformState;
    private final ObjectMapper objectMapper;

    @Autowired
    public ContextSpoolService(@Autowired(required = false) TerraformState terraformState, ObjectMapper objectMapper) {
        this.terraformState = terraformState;
        this.objectMapper = objectMapper;
    }

    public String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, Map<String, Object> patch) {
        if (terraformState == null) {
            log.warn("Cannot spool context patch: terraformState is null");
            return null;
        }
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("organizationId", organizationId);
            envelope.put("jobId", jobId);
            envelope.put("stepId", stepId);
            envelope.put("phase", phase);
            envelope.put("deliveryRevision", 1);
            envelope.put("timestamp", System.currentTimeMillis());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("envelope", envelope);
            payload.put("patch", patch);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            byte[] gzippedBytes = gzip(jsonBytes);

            return terraformState.spoolContextPatch(organizationId, jobId, stepId, phase, gzippedBytes);
        } catch (Exception e) {
            log.error("Failed to construct or spool context patch for job {} step {} phase {}: {}",
                    jobId, stepId, phase, e.getMessage(), e);
            return null;
        }
    }

    public static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            gzis.transferTo(baos);
        }
        return baos.toByteArray();
    }

    public Map<String, Object> deserializePayload(byte[] gzippedBytes) throws IOException {
        byte[] uncompressed = gunzip(gzippedBytes);
        return objectMapper.readValue(uncompressed, new TypeReference<Map<String, Object>>() {});
    }
}
