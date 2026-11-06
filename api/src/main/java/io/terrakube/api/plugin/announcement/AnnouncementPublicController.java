package io.terrakube.api.plugin.announcement;

import io.terrakube.api.repository.AnnouncementRepository;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Date;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/public/v1/announcements")
@RequiredArgsConstructor
public class AnnouncementPublicController {

    private final AnnouncementRepository announcementRepository;

    @GetMapping
    public ResponseEntity<List<PublicAnnouncementDto>> getPublicLoginAnnouncements() {
        Date now = new Date();
        List<PublicAnnouncementDto> dtos = announcementRepository
                .findActiveLoginAnnouncements(AnnouncementScope.GLOBAL, now)
                .stream()
                .map(a -> PublicAnnouncementDto.builder()
                        .id(a.getId())
                        .title(a.getTitle())
                        .message(a.getMessage())
                        .severity(a.getSeverity())
                        .startsAt(a.getStartsAt())
                        .endsAt(a.getEndsAt())
                        .updatedAt(a.getUpdatedDate())
                        .dismissible(a.isDismissible())
                        .build())
                .toList();

        return ResponseEntity.ok(dtos);
    }
}

