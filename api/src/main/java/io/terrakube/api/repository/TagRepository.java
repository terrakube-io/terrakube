package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.tag.Tag;

import java.util.List;
import java.util.UUID;

public interface TagRepository extends JpaRepository<Tag, UUID> {
    Tag getByOrganizationNameAndName(String organizationName, String name);

    List<Tag> findByOrganizationName(String organizationName);
}
