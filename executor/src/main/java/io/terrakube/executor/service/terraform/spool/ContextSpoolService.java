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
            if (jsonBytes.length > MAX_UNCOMPRESSED_BYTES) {
                log.warn("Uncompressed spool payload exceeds limit of {} bytes for job {} step {}", MAX_UNCOMPRESSED_BYTES, jobId, stepId);
                return null;
            }

            byte[] gzippedBytes = gzip(jsonBytes);
            if (gzippedBytes.length > MAX_COMPRESSED_BYTES) {
                log.warn("Compressed spool payload exceeds limit of {} bytes for job {} step {}", MAX_COMPRESSED_BYTES, jobId, stepId);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(input);
        }
        return baos.toByteArray();
    }

    public static byte[] gunzip(byte[] compressed) throws IOException {
        return gunzip(compressed, MAX_UNCOMPRESSED_BYTES);
    }

    public static byte[] gunzip(byte[] compressed, int maxUncompressedBytes) throws IOException {
        if (compressed == null) {
            return new byte[0];
        }
        if (compressed.length > MAX_COMPRESSED_BYTES) {
            throw new IOException("Compressed spool payload exceeds maximum size of " + MAX_COMPRESSED_BYTES + " bytes");
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gzis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = gzis.read(buffer)) != -1) {
                total += read;
                if (total > maxUncompressedBytes) {
                    throw new IOException("Uncompressed spool payload exceeds maximum size of " + maxUncompressedBytes + " bytes");
                }
                baos.write(buffer, 0, read);
            }
        }
        return baos.toByteArray();
    }

    public Map<String, Object> deserializePayload(byte[] gzippedBytes) throws IOException {
        byte[] uncompressed = gunzip(gzippedBytes);
        return objectMapper.readValue(uncompressed, new TypeReference<Map<String, Object>>() {});
    }
}
