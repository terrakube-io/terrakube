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

    public static final int MAX_UNCOMPRESSED_BYTES = 15 * 1024 * 1024; // 15 MB
    public static final int MAX_COMPRESSED_BYTES = 5 * 1024 * 1024; // 5 MB

    private final TerraformState terraformState;
    private final ObjectMapper objectMapper;

    @Autowired
    public ContextSpoolService(@Autowired(required = false) TerraformState terraformState, ObjectMapper objectMapper) {
        this.terraformState = terraformState;
        this.objectMapper = objectMapper;
    }

    public String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, Map<String, Object> patch) {
        return spoolContextPatch(organizationId, jobId, stepId, phase, 1L, patch);
    }

    public String spoolContextPatch(String organizationId, String jobId, String stepId, String phase, long deliveryRevision, Map<String, Object> patch) {
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
            envelope.put("deliveryRevision", deliveryRevision);
            envelope.put("timestamp", System.currentTimeMillis());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("envelope", envelope);
            payload.put("patch", patch);

            byte[] jsonBytes = objectMapper.writeValueAsBytes(payload);
            if (jsonBytes.length > SpoolCompressionUtil.MAX_UNCOMPRESSED_BYTES) {
                log.warn("Uncompressed spool payload exceeds limit of {} bytes for job {} step {}", SpoolCompressionUtil.MAX_UNCOMPRESSED_BYTES, jobId, stepId);
                return null;
            }

            byte[] gzippedBytes = SpoolCompressionUtil.gzip(jsonBytes);
            if (gzippedBytes.length > SpoolCompressionUtil.MAX_COMPRESSED_BYTES) {
                log.warn("Compressed spool payload exceeds limit of {} bytes for job {} step {}", SpoolCompressionUtil.MAX_COMPRESSED_BYTES, jobId, stepId);
                return null;
            }

            return terraformState.spoolContextPatch(organizationId, jobId, stepId, phase, gzippedBytes);
        } catch (Exception e) {
            log.error("Failed to construct or spool context patch for job {} step {} phase {}: {}",
                    jobId, stepId, phase, e.getMessage(), e);
            return null;
        }
    }

    public static byte[] gzip(byte[] input) throws IOException {
        return SpoolCompressionUtil.gzip(input);
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        return SpoolCompressionUtil.gunzip(compressed);
    }

    public static byte[] gunzip(byte[] compressed, int maxUncompressedBytes) throws IOException {
        return SpoolCompressionUtil.gunzip(compressed, maxUncompressedBytes);
    }

    public Map<String, Object> deserializePayload(byte[] gzippedBytes) throws IOException {
        byte[] uncompressed = gunzip(gzippedBytes);
        return objectMapper.readValue(uncompressed, new TypeReference<Map<String, Object>>() {});
    }
}
