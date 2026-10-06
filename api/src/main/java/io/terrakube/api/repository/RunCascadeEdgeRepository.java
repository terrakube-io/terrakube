package io.terrakube.api.repository;

import io.terrakube.api.rs.cascade.RunCascadeEdge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RunCascadeEdgeRepository extends JpaRepository<RunCascadeEdge, UUID> {

    List<RunCascadeEdge> findByCascade_Id(UUID cascadeId);
}
