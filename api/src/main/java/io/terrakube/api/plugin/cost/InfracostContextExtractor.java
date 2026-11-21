package io.terrakube.api.plugin.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.storage.StorageTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfracostContextExtractor {

    private final StorageTypeService storageTypeService;
    private final ObjectMapper objectMapper;

    public Optional<InfracostSummary> extract(int jobId) {
        if (storageTypeService == null) {
            return Optional.empty();
        }
        try {
            String contextJson = storageTypeService.getContext(jobId);
            return extractFromContextJson(contextJson);
        } catch (Exception e) {
            log.debug("Unable to read Infracost context for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<InfracostSummary> extractFromContextJson(String contextJson) {
        if (contextJson == null || contextJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            JsonNode infracost = root != null ? root.get("infracost") : null;
            if (infracost == null || !infracost.isObject()) {
                return Optional.empty();
            }

            String status = textOrNull(infracost.get("status"));
            if (status == null) {
                status = infracost.has("errorMessage") ? "ERROR" : "SUCCESS";
            }

            String currency = textOrNull(infracost.get("currency"));
            if (currency == null) {
                currency = "USD";
            }

            JsonNode summaryNode = infracost.path("summary");
            return Optional.of(new InfracostSummary(
                    status,
                    currency,
                    decimalOrNull(infracost.get("totalMonthlyCost")),
                    decimalOrNull(infracost.get("pastTotalMonthlyCost")),
                    decimalOrNull(infracost.get("diffTotalMonthlyCost")),
                    summaryNode.path("totalDetectedResources").asInt(0),
                    summaryNode.path("totalSupportedResources").asInt(0),
                    summaryNode.path("totalUnsupportedResources").asInt(0),
                    summaryNode.path("totalNoPriceResources").asInt(0),
                    textOrNull(infracost.get("shareUrl")),
                    textOrNull(infracost.get("errorMessage"))
            ));
        } catch (Exception e) {
            log.debug("Failed to parse Infracost context JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            String raw = node.asText().trim();
            if (raw.isEmpty()) {
                return null;
            }
            try {
                return new BigDecimal(raw);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
