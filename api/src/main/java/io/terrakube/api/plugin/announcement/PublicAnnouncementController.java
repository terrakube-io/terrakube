package io.terrakube.api.plugin.announcement;

import io.terrakube.api.repository.AnnouncementRepository;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementSeverity;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/public/v1/announcements")
@RequiredArgsConstructor
public class PublicAnnouncementController {

    private final AnnouncementRepository announcementRepository;

    @GetMapping
    public ResponseEntity<PublicAnnouncementsResponse> getActiveLoginAnnouncements() {
        Instant now = Instant.now();
        List<PublicAnnouncementDto> announcements = announcementRepository
                .findActiveLoginGlobalAnnouncements(Date.from(now))
                .stream()
                .map(PublicAnnouncementDto::from)
                .toList();

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .header(ServerTimeResponseFilter.SERVER_TIME_HEADER, now.toString())
                .body(new PublicAnnouncementsResponse(now, announcements));
    }

    public record PublicAnnouncementsResponse(Instant serverTime, List<PublicAnnouncementDto> announcements) {}

    public record PublicAnnouncementDto(
            UUID id,
            String title,
            String message,
            AnnouncementSeverity severity,
            Date startsAt,
            Date endsAt,
            Date updatedDate) {

        static PublicAnnouncementDto from(Announcement announcement) {
            return new PublicAnnouncementDto(
                    announcement.getId(),
                    announcement.getTitle(),
                    announcement.getMessage(),
                    announcement.getSeverity(),
                    announcement.getStartsAt(),
                    announcement.getEndsAt(),
                    announcement.getUpdatedDate());
        }
    }
}
