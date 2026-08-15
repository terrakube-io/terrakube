package io.terrakube.executor.service.terraform;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TerraformSensitivitySanitizer {

    private TerraformSensitivitySanitizer() {
    }

    /**
     * Recursively redacts sensitive values matching the provided sensitivity metadata.
     * When sensitivityMetadata is Boolean.TRUE, returns null (redacted).
     */
    public static Object sanitizeSensitiveValues(Object value, Object sensitiveMetadata) {
        if (Boolean.TRUE.equals(sensitiveMetadata)) {
            return null;
        }

        if (value instanceof Map<?, ?> valueMap) {
            Map<String, Object> sanitizedMap = new HashMap<>();
            Map<?, ?> sensitiveMap = sensitiveMetadata instanceof Map<?, ?> ? (Map<?, ?>) sensitiveMetadata : Map.of();

            valueMap.forEach((key, entryValue) -> sanitizedMap.put(
                    String.valueOf(key),
                    sanitizeSensitiveValues(entryValue, sensitiveMap.get(key))));
            return sanitizedMap;
        }

        if (value instanceof List<?> valueList) {
            List<?> sensitiveList = sensitiveMetadata instanceof List<?> ? (List<?>) sensitiveMetadata : List.of();
            List<Object> sanitizedList = new ArrayList<>();

            for (int index = 0; index < valueList.size(); index++) {
                Object sensitiveEntry = index < sensitiveList.size() ? sensitiveList.get(index) : null;
                sanitizedList.add(sanitizeSensitiveValues(valueList.get(index), sensitiveEntry));
            }

            return sanitizedList;
        }

        return value;
    }

    /**
     * Normalizes dynamic resource sensitivities (e.g., terraform_data) by propagating
     * the 'input' sensitivity to 'output' when 'output' sensitivity is empty or unset.
     */
    public static Object normalizeResourceSensitivities(String resourceType, String address, Object sensitiveRaw) {
        if (!(sensitiveRaw instanceof Map<?, ?> map)) {
            return sensitiveRaw;
        }

        Map<String, Object> result = new HashMap<>();
        map.forEach((k, v) -> result.put(String.valueOf(k), v));

        if ("terraform_data".equals(resourceType) || (address != null && address.contains("terraform_data."))) {
            Object inputSensitive = result.get("input");
            Object outputSensitive = result.get("output");
            boolean outputIsEmpty = outputSensitive == null
                    || (outputSensitive instanceof Map<?, ?> m && m.isEmpty())
                    || (outputSensitive instanceof List<?> l && l.isEmpty());
            if (inputSensitive != null && !Boolean.FALSE.equals(inputSensitive) && outputIsEmpty) {
                result.put("output", inputSensitive);
            }
        }

        return result;
    }

    /**
     * Overload for change map entries containing 'resourceType'/'type' and 'address'.
     */
    public static Object normalizeResourceSensitivities(Map<String, Object> change, Object sensitiveRaw) {
        if (change == null) {
            return sensitiveRaw;
        }
        Object resourceTypeObj = change.getOrDefault("resourceType", change.get("type"));
        String resourceType = resourceTypeObj instanceof String str ? str : null;
        Object addressObj = change.get("address");
        String address = addressObj instanceof String str ? str : null;
        return normalizeResourceSensitivities(resourceType, address, sensitiveRaw);
    }

    /**
     * Merges plan-time sensitivity metadata with state-time sensitive values.
     */
    public static Object mergeSensitiveMetadata(Object planSensitive, Object stateSensitive) {
        if (Boolean.TRUE.equals(planSensitive) || Boolean.TRUE.equals(stateSensitive)) {
            return true;
        }

        if (planSensitive instanceof Map<?, ?> || stateSensitive instanceof Map<?, ?>) {
            Map<String, Object> merged = new HashMap<>();
            if (planSensitive instanceof Map<?, ?> planMap) {
                planMap.forEach((k, v) -> merged.put(String.valueOf(k), v));
            }
            if (stateSensitive instanceof Map<?, ?> stateMap) {
                stateMap.forEach((k, v) -> {
                    String stringKey = String.valueOf(k);
                    merged.put(stringKey, mergeSensitiveMetadata(merged.get(stringKey), v));
                });
            }
            return merged;
        }

        if (planSensitive instanceof List<?> || stateSensitive instanceof List<?>) {
            List<?> planList = planSensitive instanceof List<?> list ? list : List.of();
            List<?> stateList = stateSensitive instanceof List<?> list ? list : List.of();
            int maxLength = Math.max(planList.size(), stateList.size());
            List<Object> merged = new ArrayList<>();
            for (int i = 0; i < maxLength; i++) {
                Object planVal = i < planList.size() ? planList.get(i) : null;
                Object stateVal = i < stateList.size() ? stateList.get(i) : null;
                merged.add(mergeSensitiveMetadata(planVal, stateVal));
            }
            return merged;
        }

        return stateSensitive != null ? stateSensitive : planSensitive;
    }
}
