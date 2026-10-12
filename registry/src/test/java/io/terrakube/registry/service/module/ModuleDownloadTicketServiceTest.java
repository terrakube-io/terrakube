package io.terrakube.registry.service.module;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleDownloadTicketServiceTest {

    private ModuleDownloadTicketService ticketService;
    private final String secret = Base64.getUrlEncoder().withoutPadding().encodeToString("my-super-secret-key-32-bytes-long!".getBytes());

    @BeforeEach
    void setUp() {
        ticketService = new ModuleDownloadTicketService(secret, 300);
    }

    @Test
    void testGenerateAndValidateTicket_Success() {
        String ticket = ticketService.generateTicket("my-org", "my-mod", "aws", "1.0.0");
        assertNotNull(ticket);
        assertTrue(ticketService.validateTicket(ticket, "my-org", "my-mod", "aws", "1.0.0"));
    }

    @Test
    void testValidateTicket_VersionMismatch() {
        String ticket = ticketService.generateTicket("my-org", "my-mod", "aws", "1.0.0");
        // Reusing ticket for different version must fail
        assertFalse(ticketService.validateTicket(ticket, "my-org", "my-mod", "aws", "1.0.1"));
        assertFalse(ticketService.validateTicket(ticket, "my-org", "my-mod", "aws", "2.0.0"));
    }

    @Test
    void testValidateTicket_OrganizationOrModuleMismatch() {
        String ticket = ticketService.generateTicket("my-org", "my-mod", "aws", "1.0.0");
        assertFalse(ticketService.validateTicket(ticket, "other-org", "my-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket(ticket, "my-org", "other-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket(ticket, "my-org", "my-mod", "gcp", "1.0.0"));
    }

    @Test
    void testValidateTicket_ExpiredTicket() {
        // Service with negative TTL so ticket is already expired
        ModuleDownloadTicketService expiredTicketService = new ModuleDownloadTicketService(secret, -10);
        String expiredTicket = expiredTicketService.generateTicket("my-org", "my-mod", "aws", "1.0.0");

        assertFalse(ticketService.validateTicket(expiredTicket, "my-org", "my-mod", "aws", "1.0.0"));
    }

    @Test
    void testValidateTicket_TamperedSignature() {
        String ticket = ticketService.generateTicket("my-org", "my-mod", "aws", "1.0.0");
        String tampered = ticket.substring(0, ticket.length() - 2) + "00";

        assertFalse(ticketService.validateTicket(tampered, "my-org", "my-mod", "aws", "1.0.0"));
    }

    @Test
    void testValidateTicket_MalformedTickets() {
        assertFalse(ticketService.validateTicket(null, "my-org", "my-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket("", "my-org", "my-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket("nodot", "my-org", "my-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket(".nosig", "my-org", "my-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket("notanumber.sig", "my-org", "my-mod", "aws", "1.0.0"));
        assertFalse(ticketService.validateTicket("12345.nothex!@#", "my-org", "my-mod", "aws", "1.0.0"));
    }

    @Test
    void testMissingSecretThrowsException() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ModuleDownloadTicketService("", 300));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ModuleDownloadTicketService(null, 300));
    }
}
