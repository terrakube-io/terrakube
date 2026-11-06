package io.terrakube.api.repository;

import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {

    @Query("SELECT a FROM announcement a WHERE a.scope = :scope AND a.active = true AND a.displayOnLogin = true " +
           "AND (a.startsAt IS NULL OR a.startsAt <= :now) AND (a.endsAt IS NULL OR a.endsAt >= :now)")
    List<Announcement> findActiveLoginAnnouncements(@Param("scope") AnnouncementScope scope, @Param("now") Date now);
}

