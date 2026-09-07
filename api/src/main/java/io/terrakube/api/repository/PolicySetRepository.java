package io.terrakube.api.repository;

import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.policy.PolicySet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicySetRepository extends JpaRepository<PolicySet, UUID> {
    List<PolicySet> findByOrganization(Organization organization);

    List<PolicySet> findByOrganizationAndGlobalTrue(Organization organization);
}
