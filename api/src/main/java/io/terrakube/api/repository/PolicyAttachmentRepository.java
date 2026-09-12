package io.terrakube.api.repository;

import io.terrakube.api.rs.policy.PolicyAttachment;
import io.terrakube.api.rs.policy.PolicySet;
import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.tag.Tag;
import io.terrakube.api.rs.workspace.Workspace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PolicyAttachmentRepository extends JpaRepository<PolicyAttachment, UUID> {
    List<PolicyAttachment> findByPolicySet(PolicySet policySet);

    List<PolicyAttachment> findByWorkspace(Workspace workspace);

    List<PolicyAttachment> findByProject(Project project);

    List<PolicyAttachment> findByTagIn(List<Tag> tags);
}
