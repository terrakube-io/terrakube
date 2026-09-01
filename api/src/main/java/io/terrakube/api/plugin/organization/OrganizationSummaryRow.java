package io.terrakube.api.plugin.organization;

import io.terrakube.api.rs.ExecutionMode;

import java.util.UUID;

/**
 * Lightweight database projection used by the organization summary endpoint.
 *
 * <p>Keeping this separate from {@code Organization} is intentional: touching the entity's
 * {@code workspace} relationship causes Elide/JPA to materialize and permission-check every
 * workspace just to calculate the sidebar counters.
 */
public record OrganizationSummaryRow(
        UUID id,
        String name,
        String description,
        ExecutionMode executionMode,
        String icon,
        long workspaceCount) {
}
