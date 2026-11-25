package io.terrakube.api.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.terrakube.api.rs.project.Project;
import io.terrakube.api.rs.project.tag.ProjectTag;

public interface ProjectTagRepository extends JpaRepository<ProjectTag, UUID> {

    List<ProjectTag> findByProject(Project project);
}
