package io.terrakube.api.plugin.vcs;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.terrakube.api.repository.JobRepository;
import io.terrakube.api.rs.job.Job;
import io.terrakube.api.rs.vcs.Vcs;
import io.terrakube.api.rs.workspace.Workspace;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Mints a fresh VCS token for a running job.
 *
 * Tokens are minted at dispatch and travel to the executor inside the job payload, so
 * the executor holds a string and has no way to obtain another. That is fine until the
 * token stops working mid-run - it is revoked, the installation's access is narrowed, or
 * the run outlives the hour a GitHub App token is valid for - at which point the clone
 * or fetch is refused and the run fails with nothing the executor can do about it.
 *
 * This endpoint gives it something to do: ask for a new token once, and retry. Only the
 * caller that was actually refused can know a token has gone bad, which is why this is
 * driven from the executor rather than guessed at from here.
 */
@Slf4j
@RestController
@RequestMapping("/vcs-token/v1")
@AllArgsConstructor
public class VcsTokenController {

    private final JobRepository jobRepository;
    private final TokenService tokenService;
    private final ObjectMapper objectMapper;

    @PostMapping(value = "/{jobId}/refresh", produces = MediaType.APPLICATION_JSON_VALUE)
    @Transactional
    public ResponseEntity<String> refresh(@PathVariable("jobId") int jobId) {
        Optional<Job> search = jobRepository.findById(jobId);
        if (search.isEmpty()) {
            log.warn("Cannot refresh a VCS token for missing job {}", jobId);
            return new ResponseEntity<>("{}", HttpStatus.NOT_FOUND);
        }

        Workspace workspace = search.get().getWorkspace();
        Vcs vcs = workspace != null ? workspace.getVcs() : null;
        if (vcs == null) {
            log.warn("Job {} has no VCS connection, nothing to refresh", jobId);
            return new ResponseEntity<>("{}", HttpStatus.CONFLICT);
        }

        try {
            String token = tokenService.refreshAccessToken(workspace.getSource(), vcs);
            if (token == null) {
                // Not a connection that can mint on demand (OAuth, SSH, managed identity).
                return new ResponseEntity<>("{}", HttpStatus.CONFLICT);
            }
            ObjectNode body = objectMapper.createObjectNode();
            body.put("token", token);
            log.info("Refreshed the VCS token for job {} on workspace {}", jobId, workspace.getId());
            return new ResponseEntity<>(objectMapper.writeValueAsString(body), HttpStatus.OK);
        } catch (Exception e) {
            // The executor treats any non-200 as "keep the original failure", so the
            // reason for giving up belongs in this log rather than in the response.
            log.error("Could not refresh the VCS token for job {}", jobId, e);
            return new ResponseEntity<>("{}", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
