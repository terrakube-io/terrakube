package io.terrakube.api.plugin.vcs;

import io.terrakube.api.repository.VcsRepository;
import io.terrakube.api.rs.vcs.Vcs;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/refresh-token/v1/vcs")
public class TokenServiceController {

    TokenService tokenService;
    VcsRepository vcsRepository;

    @GetMapping("/{vcsId}")
    public ResponseEntity<?> connected(@PathVariable String vcsId, @RequestParam String gitPath) {

        Vcs vcs = vcsRepository.findById(UUID.fromString(vcsId)).get();
        try {
            tokenService.getAccessToken(gitPath, vcs);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }
}
