package io.terrakube.api.plugin.cost;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.rs.job.step.Step;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InfracostContextExtractor {

    private static final String CONTEXT_KEY_PREFIX = "infracost";
    private static final String STEP_KEY_PREFIX = CONTEXT_KEY_PREFIX + "/";

    private final StorageTypeService storageTypeService;
    private final ObjectMapper objectMapper;

    public Optional<InfracostSummary> extract(int jobId) {
        return extract(jobId, List.of());
    }

    public Optional<InfracostSummary> extract(int jobId, List<Step> steps) {
        if (storageTypeService == null) {
            return Optional.empty();
        }
        try {
            String contextJson = storageTypeService.getContext(jobId);
            return extractFromContextJson(contextJson, steps);
        } catch (Exception e) {
            log.debug("Unable to read Infracost context for job {}: {}", jobId, e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<InfracostSummary> extractFromContextJson(String contextJson) {
        return extractFromContextJson(contextJson, List.of());
    }

    public Optional<InfracostSummary> extractFromContextJson(String contextJson, List<Step> steps) {
        if (contextJson == null || contextJson.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(contextJson);
            return extractFromRootNode(root, steps);
        } catch (Exception e) {
            log.debug("Failed to parse Infracost context JSON: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<InfracostSummary> extractFromRootNode(JsonNode root, List<Step> steps) {
        JsonNode infracost = resolveInfracostNode(root, steps);
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
                sanitizeShareUrl(textOrNull(infracost.get("shareUrl"))),
                textOrNull(infracost.get("errorMessage"))
        ));
    }

    private static JsonNode resolveInfracostNode(JsonNode root, List<Step> steps) {
        if (root == null || !root.isObject()) {
            return null;
        }
        if (steps != null && !steps.isEmpty()) {
            List<Step> descendingSteps = steps.stream()
                    .filter(s -> s != null && s.getId() != null)
                    .sorted(Comparator.comparingInt(Step::getStepNumber).reversed())
                    .toList();
            for (Step step : descendingSteps) {
                JsonNode stepNode = root.get(STEP_KEY_PREFIX + step.getId());
                if (stepNode != null && stepNode.isObject()) {
                    return stepNode;
                }
            }
        }
        JsonNode matchedPrefixed = null;
        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getKey().startsWith(STEP_KEY_PREFIX) && entry.getValue() != null && entry.getValue().isObject()) {
                matchedPrefixed = entry.getValue();
            }
        }
        if (matchedPrefixed != null) {
            return matchedPrefixed;
        }
        JsonNode legacyNode = root.get(CONTEXT_KEY_PREFIX);
        return (legacyNode != null && legacyNode.isObject()) ? legacyNode : null;
    }

    static String sanitizeShareUrl(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || containsUnsafeMarkdownOrWhitespace(trimmed)) {
            return null;
        }
        try {
            URI uri = URI.create(trimmed);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null) {
                return null;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            String normalizedHost = host.toLowerCase(Locale.ROOT);
            if ("infracost.io".equals(normalizedHost) || normalizedHost.endsWith(".infracost.io")) {
                return trimmed;
            }
            return null;
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static boolean containsUnsafeMarkdownOrWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isWhitespace(ch) || ch == '(' || ch == ')' || ch == '[' || ch == ']'
                    || ch == '<' || ch == '>' || ch == '"' || ch == '\'') {
                return true;
            }
        }
        return false;
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
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }
}
