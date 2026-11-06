package io.terrakube.api.plugin.announcement;

import io.terrakube.api.repository.AnnouncementRepository;
import io.terrakube.api.rs.announcement.Announcement;
import io.terrakube.api.rs.announcement.AnnouncementScope;
import io.terrakube.api.rs.announcement.AnnouncementSeverity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.ResponseEntity;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

class AnnouncementPublicControllerTest {

    private AnnouncementRepository announcementRepository;
    private AnnouncementPublicController controller;

    @BeforeEach
    void setUp() {
        announcementRepository = Mockito.mock(AnnouncementRepository.class);
        controller = new AnnouncementPublicController(announcementRepository);
    }

    @Test
    void testGetPublicLoginAnnouncements() {
        Announcement announcement = new Announcement();
        announcement.setId(UUID.randomUUID());
        announcement.setTitle("Login Banner");
        announcement.setMessage("Welcome to Terrakube");
        announcement.setSeverity(AnnouncementSeverity.info);
        announcement.setScope(AnnouncementScope.GLOBAL);
        announcement.setActive(true);
        announcement.setDisplayOnLogin(true);
        announcement.setDismissible(true);
        announcement.setCreatedDate(new Date());
        announcement.setUpdatedDate(new Date());

        when(announcementRepository.findActiveLoginAnnouncements(eq(AnnouncementScope.GLOBAL), any()))
                .thenReturn(List.of(announcement));

        ResponseEntity<List<PublicAnnouncementDto>> response = controller.getPublicLoginAnnouncements();
        assertNotNull(response);
        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size());

        PublicAnnouncementDto dto = response.getBody().get(0);
        assertEquals(announcement.getId(), dto.getId());
        assertEquals("Login Banner", dto.getTitle());
        assertEquals("Welcome to Terrakube", dto.getMessage());
        assertEquals(AnnouncementSeverity.info, dto.getSeverity());
        assertEquals(true, dto.isDismissible());
    }
}

