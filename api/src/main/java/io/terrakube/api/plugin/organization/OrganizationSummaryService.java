package io.terrakube.api.plugin.organization;

import io.terrakube.api.repository.OrganizationRepository;
import io.terrakube.api.plugin.token.team.TeamTokenService;
import io.terrakube.api.rs.job.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads the organization picker data without materializing Organization.workspace.
 *
 * <p>The restricted query duplicates the established Organization/Workspace read rules in SQL
 * predicates. In particular, counts contain only workspaces the caller can read, so a limited
 * access grant cannot reveal how many other workspaces an organization contains.
 */
@Service
@RequiredArgsConstructor
public class OrganizationSummaryService {

    private static final String INTERNAL_ISSUER = "TerrakubeInternal";

    private final OrganizationRepository organizationRepository;
    private final TeamTokenService teamTokenService;

    @Value("${io.terrakube.owner}")
    private String instanceOwner;

    @Transactional(readOnly = true)
    public List<OrganizationSummaryResponse> findSummaries(JwtAuthenticationToken principal) {
        List<String> groups = teamTokenService.getCurrentGroups(principal);
        List<OrganizationSummaryRow> organizations = isUnrestricted(principal, groups)
                ? organizationRepository.findAllSummaryRows()
                : groups.isEmpty() ? List.of() : organizationRepository.findVisibleSummaryRows(groups);

        if (organizations.isEmpty()) {
            return List.of();
        }

        List<UUID> organizationIds = organizations.stream().map(OrganizationSummaryRow::id).toList();
        List<OrganizationStatusCountRow> statusCounts = isUnrestricted(principal, groups)
                ? organizationRepository.findStatusCounts(organizationIds)
                : organizationRepository.findVisibleStatusCounts(organizationIds, groups);

        Map<UUID, Map<String, Long>> countsByOrganization = new LinkedHashMap<>();
        for (OrganizationStatusCountRow statusCount : statusCounts) {
            countsByOrganization
                    .computeIfAbsent(statusCount.organizationId(), ignored -> new LinkedHashMap<>())
                    // Workspaces created before last_job_status was introduced have a NULL value.
                    // The entity default and UI semantics both treat it as NeverExecuted.
                    .put(statusCount.status() == null ? JobStatus.NeverExecuted.name() : statusCount.status().name(),
                            statusCount.count());
        }

        return organizations.stream()
                .map(organization -> new OrganizationSummaryResponse(
                        organization.id(),
                        organization.name(),
                        organization.description(),
                        organization.executionMode(),
                        organization.icon(),
                        organization.workspaceCount(),
                        countsByOrganization.getOrDefault(organization.id(), Map.of())))
                .toList();
    }

    private boolean isUnrestricted(JwtAuthenticationToken principal, List<String> groups) {
        return INTERNAL_ISSUER.equals(principal.getTokenAttributes().get("iss"))
                || groups.contains(instanceOwner);
    }
}
