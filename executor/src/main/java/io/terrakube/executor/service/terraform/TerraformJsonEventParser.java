package io.terrakube.executor.service.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
public class TerraformJsonEventParser {

    private final ObjectMapper objectMapper;

    public TerraformJsonEventParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Parses one line of `terraform`/`tofu` `plan -json` or `apply -json` output, updating the
     * matching entry in {@code changes} (matched by "address") in place, and appending any
     * unaddressed diagnostic to {@code jobDiagnostics}. Returns the human-readable @message for
     * the caller to feed into the existing plain-text console log. Plain-text lines can still be
     * emitted by Terraform or provisioners while JSON output is enabled, so preserve those lines
     * even though they cannot update structured apply progress.
     */
    public String parseLine(String jsonLine, List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics) {
        Map<String, Object> event;
        try {
            event = objectMapper.readValue(jsonLine, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.debug("Unable to parse apply JSON line; forwarding it as plain text: {}", jsonLine);
            return jsonLine;
        }

        Object message = event.get("@message");
        String type = String.valueOf(event.get("type"));

        switch (type) {
            case "apply_start" -> updateStatus(changes, resourceAddress(event), "applying");
            case "apply_progress" -> updateElapsedSeconds(changes, resourceAddress(event), event);
            case "apply_complete" -> {
                updateStatus(changes, resourceAddress(event), "applied");
                updateElapsedSeconds(changes, resourceAddress(event), event);
            }
            case "apply_errored" -> {
                updateStatus(changes, resourceAddress(event), "errored");
                updateElapsedSeconds(changes, resourceAddress(event), event);
            }
            case "diagnostic" -> {
                attachDiagnostic(changes, jobDiagnostics, event);
                return renderDiagnostic(event, message instanceof String text ? text : null);
            }
            case "provision_start" -> updateCurrentProvisioner(changes, resourceAddress(event), provisionerName(event));
            case "provision_progress" -> appendProvisionerOutput(changes, resourceAddress(event), event);
            case "provision_complete", "provision_errored" -> updateCurrentProvisioner(changes, resourceAddress(event), null);
            case "refresh_start" -> startRefresh(changes, resourceAddress(event));
            case "refresh_complete" -> completeRefresh(changes, resourceAddress(event));
            // Terraform: ephemeral_op_start/progress/complete/errored, hook.action = open|renew|close.
            // OpenTofu (as of 1.12.5): only ephemeral_action_started/complete exist - no progress
            // event, no dedicated errored type (failures surface as a regular diagnostic event
            // instead) - and the hook has no "action" field, only a human-text "Msg" field
            // ("Opening..."/"Renewing..."/"Closing..." and their "complete" counterparts).
            // ephemeralAction() below normalizes both shapes to the same open/renew/close vocabulary.
            case "ephemeral_op_start", "ephemeral_action_started" -> updateEphemeralStatus(changes, resourceAddress(event), ephemeralStartStatus(event));
            case "ephemeral_op_progress" -> updateElapsedSeconds(changes, resourceAddress(event), event);
            case "ephemeral_op_complete", "ephemeral_action_complete" -> {
                updateEphemeralStatus(changes, resourceAddress(event), ephemeralCompleteStatus(event));
                updateElapsedSeconds(changes, resourceAddress(event), event);
            }
            case "ephemeral_op_errored" -> {
                updateEphemeralStatus(changes, resourceAddress(event), "ephemeral-errored");
                updateElapsedSeconds(changes, resourceAddress(event), event);
            }
            case "planned_change" -> applyPlannedChange(changes, event);
            case "resource_drift" -> applyResourceDrift(changes, event);
            default -> {
                // no-op: version/change_summary/outputs and any future event type from either
                // spec don't carry a resource-status transition we track.
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

    // Not every event type that carries a resource address is guaranteed to arrive after a
    // planned_change/seedFromPlan has already created that address's row: resource_drift and
    // refresh_start/refresh_complete fire during the pre-plan refresh, before planned_change ever
    // runs; ephemeral resources never get a planned_change at all (they're not part of the
    // managed-resource diff it describes) and are never seeded from the plan for apply either
    // (seedFromPlan reads that same diff). Every per-resource update in this file goes through
    // this method rather than a plain look-up, so no future event type can silently lose its
    // update the way each of the above did before this existed.
    private Map<String, Object> findOrSeedChange(List<Map<String, Object>> changes, String address) {
        for (Map<String, Object> change : changes) {
            if (address.equals(change.get("address"))) {
                return change;
            }
        }

        Map<String, Object> seeded = new HashMap<>();
        seeded.put("address", address);
        changes.add(seeded);
        return seeded;
    }

    private void updateStatus(List<Map<String, Object>> changes, String address, String status) {
        if (address == null) {
            return;
        }

        findOrSeedChange(changes, address).put("status", status);
    }

    private void updateEphemeralStatus(List<Map<String, Object>> changes, String address, String status) {
        if (address == null) {
            return;
        }

        Map<String, Object> change = findOrSeedChange(changes, address);
        change.putIfAbsent("action", "ephemeral");
        change.put("status", status);
    }

    private void updateElapsedSeconds(List<Map<String, Object>> changes, String address, Map<String, Object> event) {
        if (address == null) {
            return;
        }

        Object hookRaw = event.get("hook");
        if (!(hookRaw instanceof Map<?, ?> hook)) {
            return;
        }

        Object elapsedRaw = hook.get("elapsed_seconds");
        if (!(elapsedRaw instanceof Number elapsedNumber)) {
            return;
        }

        findOrSeedChange(changes, address).put("elapsedSeconds", elapsedNumber.intValue());
    }

    private void attachDiagnostic(List<Map<String, Object>> changes, List<Map<String, Object>> jobDiagnostics, Map<String, Object> event) {
        Object diagnosticRaw = event.get("diagnostic");
        if (!(diagnosticRaw instanceof Map<?, ?> diagnostic)) {
            return;
        }

        String severity = severityOrNull(diagnostic.get("severity"));
        if (severity == null) {
            return;
        }

        Object summary = diagnostic.get("summary");
        if (!(summary instanceof String summaryString)) {
            return;
        }

        Map<String, Object> diagnosticEntry = new HashMap<>();
        diagnosticEntry.put("severity", severity);
        diagnosticEntry.put("summary", summaryString);
        if (diagnostic.get("detail") instanceof String detailString) {
            diagnosticEntry.put("detail", detailString);
        }
        String location = diagnosticLocation(diagnostic);
        if (location != null) {
            diagnosticEntry.put("location", location);
        }

        Object addressRaw = diagnostic.get("address");
        if (addressRaw instanceof String addressString) {
            // findOrSeedChange covers the case where no planned_change has been seen yet for
            // this address - typically because evaluation errored before Terraform could
            // determine an action (e.g. a provider that can't authenticate) - so the resource
            // still surfaces instead of the diagnostic (and the only record of this resource
            // even being part of the run) being silently dropped. Only set status on a freshly
            // seeded entry (no status key yet) - an already-seeded resource keeps whatever
            // status its own events already gave it.
            Map<String, Object> change = findOrSeedChange(changes, addressString);
            if ("error".equals(severity) && !change.containsKey("status")) {
                change.put("status", "errored");
            }
            addDiagnostic(change, diagnosticEntry);
            return;
        }

        jobDiagnostics.add(diagnosticEntry);
    }

    // Under `-json` a diagnostic's own @message is only the one-line "Error: <summary>" /
    // "Warning: <summary>" header - the file/line, the source snippet and the explanatory detail
    // all live in the structured "diagnostic" object and would otherwise never reach the console
    // stream. That stream is what `terraform`/`tofu` prints back to a CLI-driven plan, what the
    // raw-log download serves, and what PrCommentService posts, so rebuild the full multi-line
    // rendering here, close to what plain (non-json) terraform would have printed. Falls back to
    // the raw @message if the structured object is missing or malformed.
    private String renderDiagnostic(Map<String, Object> event, String fallbackMessage) {
        Object diagnosticRaw = event.get("diagnostic");
        if (!(diagnosticRaw instanceof Map<?, ?> diagnostic)) {
            return fallbackMessage;
        }

        String severity = severityOrNull(diagnostic.get("severity"));
        if (severity == null || !(diagnostic.get("summary") instanceof String summary)) {
            return fallbackMessage;
        }

        StringBuilder rendered = new StringBuilder();
        rendered.append('\n')
                .append("error".equals(severity) ? "Error: " : "Warning: ")
                .append(summary)
                .append('\n');

        String location = renderDiagnosticSource(diagnostic);
        if (location != null) {
            rendered.append('\n').append(location).append('\n');
        }

        if (diagnostic.get("detail") instanceof String detail && !detail.isBlank()) {
            rendered.append('\n').append(detail).append('\n');
        }

        return rendered.toString();
    }

    // "  on main.tf line 12, in resource "aws_instance" "web":" followed by the numbered source
    // lines - the same layout terraform's own console renderer uses. Degrades to just the
    // "  on <file> line <n>:" header when the event carries a range but no code snippet, and to
    // null when the diagnostic has no source location at all (a config-wide problem).
    private String renderDiagnosticSource(Map<?, ?> diagnostic) {
        if (!(diagnostic.get("range") instanceof Map<?, ?> range)
                || !(range.get("filename") instanceof String filename)) {
            return null;
        }

        Integer line = null;
        if (range.get("start") instanceof Map<?, ?> start && start.get("line") instanceof Number lineNumber) {
            line = lineNumber.intValue();
        }

        StringBuilder source = new StringBuilder("  on ").append(filename);
        if (line != null) {
            source.append(" line ").append(line);
        }

        Map<?, ?> snippet = diagnostic.get("snippet") instanceof Map<?, ?> s ? s : null;
        if (snippet != null && snippet.get("context") instanceof String context && !context.isBlank()) {
            source.append(", in ").append(context);
        }
        source.append(':');

        if (snippet != null && snippet.get("code") instanceof String code) {
            int startLine = snippet.get("start_line") instanceof Number n ? n.intValue()
                    : (line != null ? line : 1);
            String[] codeLines = code.split("\n", -1);
            for (int i = 0; i < codeLines.length; i++) {
                source.append('\n').append(String.format("%4d: %s", startLine + i, codeLines[i]));
            }
        }

        return source.toString();
    }

    // Diagnostics that carry no resource address at all (a deprecated variable/output, which can
    // be referenced from many places) have no other attribution in Terraform's JSON UI protocol -
    // "range" (file + line) is the only way to tell two such warnings apart.
    private String diagnosticLocation(Map<?, ?> diagnostic) {
        Object rangeRaw = diagnostic.get("range");
        if (!(rangeRaw instanceof Map<?, ?> range)) {
            return null;
        }

        Object filenameRaw = range.get("filename");
        if (!(filenameRaw instanceof String filename)) {
            return null;
        }

        Object startRaw = range.get("start");
        if (startRaw instanceof Map<?, ?> start && start.get("line") instanceof Number lineNumber) {
            return filename + ":" + lineNumber.intValue();
        }

        return filename;
    }

    private String severityOrNull(Object severityRaw) {
        if ("error".equals(severityRaw)) {
            return "error";
        }
        if ("warning".equals(severityRaw)) {
            return "warning";
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addDiagnostic(Map<String, Object> change, Map<String, Object> diagnosticEntry) {
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) change
                .computeIfAbsent("diagnostics", key -> new ArrayList<Map<String, Object>>());
        diagnostics.add(diagnosticEntry);
    }

    private String provisionerName(Map<String, Object> event) {
        Object hookRaw = event.get("hook");
        if (!(hookRaw instanceof Map<?, ?> hook)) {
            return null;
        }
        Object provisioner = hook.get("provisioner");
        return provisioner instanceof String provisionerString ? provisionerString : null;
    }

    private void updateCurrentProvisioner(List<Map<String, Object>> changes, String address, String provisioner) {
        if (address == null) {
            return;
        }

        Map<String, Object> change = findOrSeedChange(changes, address);
        if (provisioner == null) {
            change.remove("currentProvisioner");
        } else {
            change.put("currentProvisioner", provisioner);
        }
    }

    @SuppressWarnings("unchecked")
    private void appendProvisionerOutput(List<Map<String, Object>> changes, String address, Map<String, Object> event) {
        if (address == null) {
            return;
        }

        Object hookRaw = event.get("hook");
        if (!(hookRaw instanceof Map<?, ?> hook)) {
            return;
        }

        Object outputRaw = hook.get("output");
        if (!(outputRaw instanceof String outputString)) {
            return;
        }

        Map<String, Object> change = findOrSeedChange(changes, address);
        List<String> output = (List<String>) change.computeIfAbsent("provisionerOutput", key -> new ArrayList<String>());
        output.add(outputString);
    }

    private void startRefresh(List<Map<String, Object>> changes, String address) {
        if (address == null) {
            return;
        }

        Map<String, Object> change = findOrSeedChange(changes, address);
        // A resource that's only ever refreshed - never followed by planned_change/apply_start -
        // is by construction unchanged. Default a freshly-seeded entry's action to "no-op" so it
        // renders as such if nothing else ever sets it; putIfAbsent means a real action from a
        // later event (or an already-known one from an earlier seed) is never overwritten by this.
        // Without this, buildChangesFromPlanJson's deliberate no-op filtering (skipping unchanged
        // resources so a large plan's full-state refresh doesn't clutter the list) left these
        // entries seeded here with no action at all, rendering as an unlabeled "?" badge.
        change.putIfAbsent("action", "no-op");
        Object currentStatus = change.getOrDefault("status", "pending");
        change.put("previousStatus", currentStatus);
        change.put("status", "refreshing");
    }

    private void completeRefresh(List<Map<String, Object>> changes, String address) {
        if (address == null) {
            return;
        }

        Map<String, Object> change = findOrSeedChange(changes, address);
        Object previousStatus = change.remove("previousStatus");
        change.put("status", previousStatus != null ? previousStatus : "pending");
    }

    private String ephemeralStartStatus(Map<String, Object> event) {
        return switch (ephemeralAction(event)) {
            case "renew" -> "ephemeral-opening"; // renewing reuses the "in progress" badge; there's
                                                  // no meaningful visual difference for a brief renew tick
            case "close" -> "ephemeral-closing";
            default -> "ephemeral-opening";
        };
    }

    private String ephemeralCompleteStatus(Map<String, Object> event) {
        return switch (ephemeralAction(event)) {
            case "renew" -> "ephemeral-renewed";
            case "close" -> "applied"; // closed-and-gone; nothing more to show, treat as done
            default -> "applied";
        };
    }

    private String ephemeralAction(Map<String, Object> event) {
        Object hookRaw = event.get("hook");
        if (!(hookRaw instanceof Map<?, ?> hook)) {
            return "";
        }

        Object action = hook.get("action");
        if (action instanceof String actionString) {
            return actionString;
        }

        // OpenTofu's ephemeral hook has no "action" field (unlike Terraform's) - only a "Msg"
        // field with human text ("Opening...", "Renewing...", "Closing...", and their "complete"
        // counterparts) - derive the open/renew/close phase from that instead.
        Object msg = hook.get("Msg");
        if (msg instanceof String msgString) {
            String normalized = msgString.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("renew")) {
                return "renew";
            }
            if (normalized.startsWith("clos")) {
                return "close";
            }
            if (normalized.startsWith("open")) {
                return "open";
            }
        }

        return "";
    }

    private void applyPlannedChange(List<Map<String, Object>> changes, Map<String, Object> event) {
        String address = changeAddress(event);
        String action = changeAction(event);
        if (address == null) {
            return;
        }

        Map<String, Object> change = findOrSeedChange(changes, address);
        if (action != null) {
            change.put("action", action);
        }
        change.put("status", "planned");
    }

    private void applyResourceDrift(List<Map<String, Object>> changes, Map<String, Object> event) {
        String address = changeAddress(event);
        String action = changeAction(event);
        if (address == null || action == null) {
            return;
        }

        findOrSeedChange(changes, address).put("driftAction", action);
    }

    private String changeAddress(Map<String, Object> event) {
        Object changeRaw = event.get("change");
        if (!(changeRaw instanceof Map<?, ?> change)) {
            return null;
        }
        Object resourceRaw = change.get("resource");
        if (!(resourceRaw instanceof Map<?, ?> resource)) {
            return null;
        }
        Object addr = resource.get("addr");
        return addr instanceof String ? (String) addr : null;
    }

    private String changeAction(Map<String, Object> event) {
        Object changeRaw = event.get("change");
        if (!(changeRaw instanceof Map<?, ?> change)) {
            return null;
        }
        Object action = change.get("action");
        return action instanceof String ? (String) action : null;
    }
}
