package io.terrakube.api.plugin.announcement;

import io.terrakube.api.rs.announcement.AnnouncementSeverity;
import lombok.Builder;
import lombok.Getter;

import java.util.Date;
import java.util.UUID;

@Getter
@Builder
public class PublicAnnouncementDto {
    private UUID id;
    private String title;
    private String message;
    private AnnouncementSeverity severity;
    private Date startsAt;
    private Date endsAt;
    private Date updatedAt;
    private boolean dismissible;
}

