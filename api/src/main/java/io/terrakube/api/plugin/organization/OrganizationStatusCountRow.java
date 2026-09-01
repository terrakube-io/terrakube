package io.terrakube.api.plugin.organization;

import io.terrakube.api.rs.job.JobStatus;

import java.util.UUID;

/** Aggregate workspace status count for one organization. */
public record OrganizationStatusCountRow(UUID organizationId, JobStatus status, long count) {
}
