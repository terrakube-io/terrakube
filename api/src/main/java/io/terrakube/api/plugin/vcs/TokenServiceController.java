package io.terrakube.api.plugin.vcs;

import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/refresh-token/v1/vcs")
public class TokenServiceController {

    TokenService tokenService;
    VcsRepository vcsRepository;

    @PostMapping("/{vcsId}")
    public ResponseEntity<?> refreshToken(@PathVariable String vcsId, @RequestBody RefreshTokenRequest refreshTokenRequest) {
        log.info("Refreshing access token for VCS {}", vcsId);
        Optional<Vcs> vcs = vcsRepository.findById(UUID.fromString(vcsId));
        if (vcs.isEmpty()) return ResponseEntity.notFound().build();
        try {
            log.info("Refreshing access token for Git path: {}", refreshTokenRequest.getGitPath());
            tokenService.getAccessToken(refreshTokenRequest.getGitPath(), vcs.get());
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}

@Data
class RefreshTokenRequest {
    String gitPath;
}
