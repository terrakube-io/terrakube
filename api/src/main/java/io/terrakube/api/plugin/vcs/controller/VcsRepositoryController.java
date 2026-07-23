package io.terrakube.api.plugin.vcs.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.terrakube.api.plugin.vcs.discovery.VcsDiscoveryNotSupportedException;
import io.terrakube.api.plugin.vcs.discovery.VcsGroupSummary;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryDiscoveryFacade;
import io.terrakube.api.plugin.vcs.discovery.VcsRepositoryPage;
import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/vcs/v1")
public class VcsRepositoryController {

    private final VcsRepositoryDiscoveryFacade discoveryFacade;
    private final VcsRepository vcsRepository;

    public VcsRepositoryController(VcsRepositoryDiscoveryFacade discoveryFacade, VcsRepository vcsRepository) {
        this.discoveryFacade = discoveryFacade;
        this.vcsRepository = vcsRepository;
    }

    @GetMapping("/{vcsId}/groups")
    @PreAuthorize("@vcsRepositoryAccessService.hasViewPermission(authentication, #vcsId)")
    public ResponseEntity<List<VcsGroupSummary>> listGroups(@PathVariable String vcsId) {
        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).orElse(null);
        if (vcs == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(discoveryFacade.listGroups(vcs));
        } catch (VcsDiscoveryNotSupportedException e) {
            return ResponseEntity.unprocessableEntity().body(List.of());
        } catch (Exception e) {
            log.error("Error listing VCS groups for vcs {}: {}", vcsId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }

    @GetMapping("/{vcsId}/repositories")
    @PreAuthorize("@vcsRepositoryAccessService.hasViewPermission(authentication, #vcsId)")
    public ResponseEntity<VcsRepositoryPage> listRepositories(@PathVariable String vcsId,
            @RequestParam String group,
            @RequestParam(required = false, defaultValue = "") String search,
            @RequestParam(required = false, defaultValue = "1") int page) {
        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).orElse(null);
        if (vcs == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(discoveryFacade.listRepositories(vcs, group, search, page));
        } catch (VcsDiscoveryNotSupportedException e) {
            return ResponseEntity.unprocessableEntity()
                    .body(VcsRepositoryPage.builder().items(List.of()).page(page).hasMore(false).build());
        } catch (Exception e) {
            log.error("Error listing repositories for vcs {}: {}", vcsId, e.getMessage());
            return ResponseEntity.internalServerError().build();
        }
    }
}
