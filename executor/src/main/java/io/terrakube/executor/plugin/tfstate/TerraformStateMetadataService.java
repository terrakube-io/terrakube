package io.terrakube.executor.plugin.tfstate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class TerraformStateMetadataService {

    private final ObjectMapper objectMapper;

    public TerraformStateMetadataService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    public TerraformStateMetadata extractMetadata(String rawState) {
        int serial = 1;
        String lineage = "0";
        String md5 = "0";

        if (rawState != null && !rawState.isBlank()) {
            try {
                md5 = DigestUtils.md5Hex(rawState);
                JsonNode root = objectMapper.readTree(rawState);
                if (root.has("serial") && !root.get("serial").isNull()) {
                    serial = root.get("serial").asInt(1);
                }
                if (root.has("lineage") && !root.get("lineage").isNull()) {
                    lineage = root.get("lineage").asText("0");
                }
            } catch (Exception e) {
                log.warn("Failed to extract serial or lineage from raw terraform state: {}", e.getMessage());
            }
        }

        return TerraformStateMetadata.builder()
                .serial(serial)
                .lineage(lineage)
                .md5(md5)
                .build();
    }
}
