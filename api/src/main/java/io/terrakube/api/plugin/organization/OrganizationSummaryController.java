package io.terrakube.api.plugin.organization;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/** UI-optimized replacement for loading organizations with every workspace relationship. */
@RestController
@RequestMapping("/ui/v1/organizations")
@RequiredArgsConstructor
public class OrganizationSummaryController {

    private final OrganizationSummaryService organizationSummaryService;

    @GetMapping("/summary")
    public ResponseEntity<List<OrganizationSummaryResponse>> listSummary(Principal principal) {
        return ResponseEntity.ok(organizationSummaryService.findSummaries((JwtAuthenticationToken) principal));
    }
}
