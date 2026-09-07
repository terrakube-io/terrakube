package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.policy.PolicyExemption;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface PolicyExemptionRepository extends JpaRepository<PolicyExemption, UUID> {
    List<PolicyExemption> findByOrganization(Organization organization);

    List<PolicyExemption> findByPolicySet(PolicySet policySet);

    List<PolicyExemption> findByWorkspace(Workspace workspace);

    List<PolicyExemption> findByProject(Project project);

    @Query("SELECT pe FROM policy_exemption pe WHERE pe.organization = :org " +
           "AND (pe.expiresAt IS NULL OR pe.expiresAt > :now) " +
           "AND (pe.workspace = :workspace OR (pe.project IS NOT NULL AND pe.project = :project) OR (pe.workspace IS NULL AND pe.project IS NULL))")
    List<PolicyExemption> findActiveExemptionsForWorkspace(
            @Param("org") Organization org,
            @Param("workspace") Workspace workspace,
            @Param("project") Project project,
            @Param("now") Date now);
}
