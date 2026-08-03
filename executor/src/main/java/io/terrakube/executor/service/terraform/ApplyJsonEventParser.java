package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

@Slf4j
public class ApplyJsonEventParser {

    private final ObjectMapper objectMapper;

    public ApplyJsonEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses one line of `terraform apply -json` output, updating the matching entry in
     * {@code changes} (matched by "address") in place. Returns the human-readable @message
     * for the caller to feed into the existing plain-text console log, or null if the line
     * wasn't parseable JSON.
     */
    public String parseLine(String jsonLine, List<Map<String, Object>> changes) {
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(jsonLine, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Unable to parse apply JSON line: {}", jsonLine, e);
            return null;
        }

        Object message = event.get("@message");
        String type = String.valueOf(event.get("type"));

        switch (type) {
            case "apply_start" -> updateStatus(changes, resourceAddress(event), "applying");
            case "apply_complete" -> updateStatus(changes, resourceAddress(event), "applied");
            case "apply_errored" -> updateStatus(changes, resourceAddress(event), "errored");
            case "diagnostic" -> attachDiagnostic(changes, event);
            default -> {
                // no-op: version/planned_change/change_summary/refresh_*/provision_*/outputs
                // events don't carry a resource-status transition we track.
            }
        }

        return message instanceof String text ? text : null;
    }

    private String resourceAddress(Map<String, Object> event) {
        Object hookRaw = event.get("hook");
        if (!(hookRaw instanceof Map<?, ?> hook)) {
            return null;
        }

        Object resourceRaw = hook.get("resource");
        if (!(resourceRaw instanceof Map<?, ?> resource)) {
            return null;
        }

        Object addr = resource.get("addr");
        return addr instanceof String ? (String) addr : null;
    }

    private void updateStatus(List<Map<String, Object>> changes, String address, String status) {
        if (address == null) {
            return;
        }

        for (Map<String, Object> change : changes) {
            if (address.equals(change.get("address"))) {
                change.put("status", status);
                return;
            }
        }
    }

    private void attachDiagnostic(List<Map<String, Object>> changes, Map<String, Object> event) {
        Object diagnosticRaw = event.get("diagnostic");
        if (!(diagnosticRaw instanceof Map<?, ?> diagnostic)) {
            return;
        }

        Object severity = diagnostic.get("severity");
        if (!"error".equals(severity)) {
            return;
        }

        Object address = diagnostic.get("address");
        Object summary = diagnostic.get("summary");
        if (!(address instanceof String addressString) || !(summary instanceof String summaryString)) {
            return;
        }

        for (Map<String, Object> change : changes) {
            if (addressString.equals(change.get("address"))) {
                change.put("error", summaryString);
                return;
            }
        }
    }
}
