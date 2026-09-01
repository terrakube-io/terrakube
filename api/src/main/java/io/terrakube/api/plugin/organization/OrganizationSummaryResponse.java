package io.terrakube.api.plugin.organization;

import io.terrakube.api.rs.ExecutionMode;

import java.util.Map;
import java.util.UUID;

/** JSON contract for the UI's organization picker and sidebar. */
public record OrganizationSummaryResponse(
        UUID id,
        String name,
        String description,
        ExecutionMode executionMode,
        String icon,
        long workspaceCount,
        Map<String, Long> statusCounts) {
}
