package io.terrakube.api.plugin.redirect;

import io.terrakube.api.plugin.security.job.JobLogAccessService;
import io.terrakube.api.repository.JobRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@Slf4j
@RestController
@RequestMapping("/app")
public class RedirectController {
    private final String uiURL;

    @Autowired
    public RedirectController(@Value("${io.terrakube.ui.url}") String uiURL) {
        this.uiURL = uiURL;
    }

    public RedirectController(JobRepository jobRepository, JobLogAccessService jobLogAccessService, String uiURL) {
        this(uiURL);
    }

    @GetMapping(path = "/{organizationName}/{workspaceName}/runs/{jobId}")
    public ResponseEntity<Void> jobIdRedirect(
            @PathVariable("organizationName") String organizationName,
            @PathVariable("workspaceName") String workspaceName,
            @PathVariable("jobId") String jobId) {
        log.info("Redirect for: {}/{}/{}", organizationName, workspaceName, jobId);
        String cleanJobId = jobId.replace("run-", "");
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(String.format("%s/app/%s/%s/runs/%s", uiURL, organizationName, workspaceName, cleanJobId)))
                .build();
    }
}
