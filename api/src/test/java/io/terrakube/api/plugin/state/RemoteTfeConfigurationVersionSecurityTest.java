package io.terrakube.api.plugin.state;

import io.terrakube.api.plugin.storage.StorageTypeService;
import io.terrakube.api.repository.ContentRepository;
import io.terrakube.api.repository.WorkspaceRepository;
import io.terrakube.api.rs.Organization;
import io.terrakube.api.rs.workspace.Workspace;
import io.terrakube.api.rs.workspace.content.Content;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RemoteTfeConfigurationVersionSecurityTest {

    @Mock
    private ContentRepository contentRepository;

    @Mock
    private StorageTypeService storageTypeService;

    @Mock
    private WorkspaceRepository workspaceRepository;

    private ConfigurationUploadTicketService ticketService;
    private RemoteTfeService remoteTfeService;

    @BeforeEach
    void setUp() {
        ticketService = new ConfigurationUploadTicketService("test-secret-key-12345678901234567890", 3600);
        remoteTfeService = new RemoteTfeService(
                null, contentRepository, null, workspaceRepository,
                null, null, null, "terrakube.example.com",
                storageTypeService, null, null, 1,
                null, null, null, null,
                null, null, null, null,
                null, null, null, null
        );
        remoteTfeService.setUploadTicketService(ticketService);
    }

    @Test
    void testInitialUploadSucceeds() {
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setStatus("pending");
        content.setSource("tfe-api");

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));
        when(contentRepository.getReferenceById(contentId)).thenReturn(content);

        String ticket = ticketService.generateTicket(contentId.toString());
        InputStream stream = new ByteArrayInputStream("fake-tar-gz-content".getBytes(StandardCharsets.UTF_8));

        remoteTfeService.uploadFile(contentId.toString(), ticket, stream);

        assertEquals("uploaded", content.getStatus());
        verify(storageTypeService).createContentFile(eq(contentId.toString()), any(InputStream.class));
        verify(contentRepository).save(content);
    }

    @Test
    void testDuplicateUploadToAlreadyUploadedContentReturnsConflict() {
        UUID contentId = UUID.randomUUID();
        Content content = new Content();
        content.setId(contentId);
        content.setStatus("uploaded"); // Already uploaded!

        when(contentRepository.findById(contentId)).thenReturn(Optional.of(content));

        String ticket = ticketService.generateTicket(contentId.toString());
        InputStream stream = new ByteArrayInputStream("malicious-overwrite".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                remoteTfeService.uploadFile(contentId.toString(), ticket, stream)
        );

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        verify(storageTypeService, never()).createContentFile(any(), any());
        verify(contentRepository, never()).save(any());
    }

    @Test
    void testUploadWithoutTicketReturnsUnauthorized() {
        UUID contentId = UUID.randomUUID();
        InputStream stream = new ByteArrayInputStream("fake-content".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException ex1 = assertThrows(ResponseStatusException.class, () ->
                remoteTfeService.uploadFile(contentId.toString(), null, stream)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex1.getStatusCode());

        ResponseStatusException ex2 = assertThrows(ResponseStatusException.class, () ->
                remoteTfeService.uploadFile(contentId.toString(), "   ", stream)
        );
        assertEquals(HttpStatus.UNAUTHORIZED, ex2.getStatusCode());
        verify(storageTypeService, never()).createContentFile(any(), any());
    }

    @Test
    void testUploadToNonExistentContentReturnsNotFound() {
        UUID contentId = UUID.randomUUID();
        when(contentRepository.findById(contentId)).thenReturn(Optional.empty());

        String ticket = ticketService.generateTicket(contentId.toString());
        InputStream stream = new ByteArrayInputStream("fake-content".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                remoteTfeService.uploadFile(contentId.toString(), ticket, stream)
        );

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(storageTypeService, never()).createContentFile(any(), any());
    }

    @Test
    void testUploadWithExpiredTicketReturnsGone() {
        UUID contentId = UUID.randomUUID();

        // Create an expired ticket service (TTL = -10 seconds)
        ConfigurationUploadTicketService expiredTicketService =
                new ConfigurationUploadTicketService("test-secret-key-12345678901234567890", -10);
        String expiredTicket = expiredTicketService.generateTicket(contentId.toString());

        InputStream stream = new ByteArrayInputStream("fake-content".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                remoteTfeService.uploadFile(contentId.toString(), expiredTicket, stream)
        );

        assertEquals(HttpStatus.GONE, ex.getStatusCode());
        verify(storageTypeService, never()).createContentFile(any(), any());
    }

    @Test
    void testUploadWithTamperedTicketReturnsForbidden() {
        UUID contentId = UUID.randomUUID();

        String validTicket = ticketService.generateTicket(contentId.toString());
        String forgedTicket = validTicket.substring(0, validTicket.length() - 4) + "0000";

        InputStream stream = new ByteArrayInputStream("fake-content".getBytes(StandardCharsets.UTF_8));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                remoteTfeService.uploadFile(contentId.toString(), forgedTicket, stream)
        );

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verify(storageTypeService, never()).createContentFile(any(), any());
    }
}
