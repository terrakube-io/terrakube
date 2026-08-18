package io.terrakube.api.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import io.terrakube.api.rs.notification.NotificationTrigger;

public interface NotificationTriggerRepository extends JpaRepository<NotificationTrigger, UUID> {
}
