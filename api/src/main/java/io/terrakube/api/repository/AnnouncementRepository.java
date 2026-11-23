package io.terrakube.api.repository;

import io.terrakube.api.rs.announcement.Announcement;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    @Query("""
            SELECT a FROM announcement a
            WHERE a.scope = io.terrakube.api.rs.announcement.AnnouncementScope.GLOBAL
              AND a.active = true
              AND a.displayOnLogin = true
              AND (a.startsAt IS NULL OR a.startsAt <= :now)
              AND (a.endsAt IS NULL OR a.endsAt > :now)
            ORDER BY
              CASE a.severity
                WHEN io.terrakube.api.rs.announcement.AnnouncementSeverity.CRITICAL THEN 0
                WHEN io.terrakube.api.rs.announcement.AnnouncementSeverity.WARNING THEN 1
                ELSE 2
              END ASC,
              CASE WHEN a.startsAt IS NULL THEN 1 ELSE 0 END ASC,
              a.startsAt DESC,
              a.createdDate DESC
            """)
    List<Announcement> findActiveLoginGlobalAnnouncements(@Param("now") Date now);

    // Native query so Hibernate's @SQLRestriction("disabled = false") on Organization
    // does not hide announcements belonging to an organization being soft-deleted.
    @Modifying(flushAutomatically = true)
    @Transactional
    @Query(value = "DELETE FROM announcement WHERE organization_id = :organizationId", nativeQuery = true)
    void deleteByOrganizationId(@Param("organizationId") String organizationId);
}
