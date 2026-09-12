package io.terrakube.api.repository;

import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.policy.PolicySetParameter;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicySetParameterRepository extends JpaRepository<PolicySetParameter, UUID> {
    List<PolicySetParameter> findByPolicySet(PolicySet policySet);

    void deleteByPolicySet(PolicySet policySet);
}
