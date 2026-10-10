package io.terrakube.api.plugin.redirect;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class RedirectControllerSecurityTest {

    private RedirectController redirectController;

    private final String uiUrl = "https://ui.terrakube.example.com";

    @BeforeEach
    void setUp() {
        redirectController = new RedirectController(uiUrl);
    }

    @Test
    void testJobIdRedirect_StripsRunPrefix() {
        ResponseEntity<Void> response = redirectController.jobIdRedirect("my-org", "my-ws", "run-123");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertNotNull(response.getHeaders().getLocation());
        assertEquals(URI.create(uiUrl + "/app/my-org/my-ws/runs/123"), response.getHeaders().getLocation());
    }

    @Test
    void testJobIdRedirect_PlainIntegerJobId() {
        ResponseEntity<Void> response = redirectController.jobIdRedirect("my-org", "my-ws", "456");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertNotNull(response.getHeaders().getLocation());
        assertEquals(URI.create(uiUrl + "/app/my-org/my-ws/runs/456"), response.getHeaders().getLocation());
    }

    @Test
    void testJobIdRedirect_PreservesOrganizationAndWorkspaceNames() {
        ResponseEntity<Void> response = redirectController.jobIdRedirect("FinanceOrg", "ProductionCluster", "run-999");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        assertNotNull(response.getHeaders().getLocation());
        assertEquals(URI.create(uiUrl + "/app/FinanceOrg/ProductionCluster/runs/999"), response.getHeaders().getLocation());
    }

    @Test
    void testJobIdRedirect_DoesNotDiscloseInternalUUIDs() {
        // Any requested path returns a direct pass-through URL to the UI without querying database or exposing internal UUIDs
        ResponseEntity<Void> response = redirectController.jobIdRedirect("attacker-org", "target-ws", "run-1");
        assertEquals(HttpStatus.FOUND, response.getStatusCode());
        String location = response.getHeaders().getLocation().toString();
        assertEquals(uiUrl + "/app/attacker-org/target-ws/runs/1", location);
        // Ensure no internal database UUID pattern is disclosed
        org.junit.jupiter.api.Assertions.assertFalse(location.matches(".*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}.*"));
    }
}
