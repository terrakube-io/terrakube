package io.terrakube.api.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import io.terrakube.api.rs.collection.item.Item;

import java.util.UUID;

public interface ItemRepository extends JpaRepository<Item, UUID> {
}
