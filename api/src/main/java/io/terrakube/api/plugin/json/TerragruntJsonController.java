package io.terrakube.api.plugin.json;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FileUtils;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/terragrunt")
public class TerragruntJsonController {

    private static final String TERRAGRUNT_REDIS_KEY = "terragruntReleasesResponse";
    private static final String TERRAGRUNT_STALE_REDIS_KEY = "terragruntReleasesResponseStale";
    TerragruntJsonProperties terragruntJsonProperties;
    RedisTemplate redisTemplate;
    DownloadReleasesService downloadReleasesService;

    @GetMapping(value = "/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTerragruntReleases() throws IOException {
        String terragruntIndex = "";
        if (redisTemplate.hasKey(TERRAGRUNT_REDIS_KEY)) {
            log.info("Getting terragrunt releases from redis....");
            String terragruntRedis = (String) redisTemplate.opsForValue().get(TERRAGRUNT_REDIS_KEY);
            return new ResponseEntity<>(terragruntRedis, HttpStatus.OK);
        } else {
            log.info("Getting terragrunt releases from default endpoint....");
            if (terragruntJsonProperties.getReleasesUrl() != null && !terragruntJsonProperties.getReleasesUrl().isEmpty()) {
                log.info("Using terragrunt releases URL {}", terragruntJsonProperties.getReleasesUrl());
                terragruntIndex = terragruntJsonProperties.getReleasesUrl();
            } else {
                terragruntIndex = "https://api.github.com/repos/gruntwork-io/terragrunt/releases";
                log.warn("Using terragrunt releases URL {}", terragruntIndex);
            }

            try {
                Path pathTmp = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
                String tmpdir = Files.createDirectories(pathTmp).toFile().getAbsolutePath() + "/terragrunt-releases.json";
                log.info("Downloading terragrunt releases to {}", tmpdir);
                File terragruntReleasesFile = new File(tmpdir);

                String githubToken = terragruntJsonProperties.getGithubToken();
                if (githubToken != null && !githubToken.isEmpty()) {
                    log.info("Using authenticated GitHub API request");
                    downloadReleasesService.downloadReleasesToFile(terragruntIndex, terragruntReleasesFile, githubToken);
                } else {
                    log.warn("No GitHub token configured - using unauthenticated request (subject to rate limits)");
                    downloadReleasesService.downloadReleasesToFile(terragruntIndex, terragruntReleasesFile);
                }

                log.info("Downloaded terragrunt releases completed");
                terragruntIndex = FileUtils.readFileToString(terragruntReleasesFile, "UTF-8");
                log.info("Reading terragrunt releases completed");
                Files.deleteIfExists(terragruntReleasesFile.toPath());
                log.info("Deleting temporary terragrunt files completed");

                log.warn("Saving terragrunt releases to redis...");
                redisTemplate.opsForValue().set(TERRAGRUNT_REDIS_KEY, terragruntIndex);
                redisTemplate.expire(TERRAGRUNT_REDIS_KEY, terragruntJsonProperties.getCacheExpirationMinutes(), TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(TERRAGRUNT_STALE_REDIS_KEY, terragruntIndex);
                return new ResponseEntity<>(terragruntIndex, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Failed to fetch Terragrunt releases from GitHub API: {}", e.getMessage());
                String staleData = (String) redisTemplate.opsForValue().get(TERRAGRUNT_STALE_REDIS_KEY);
                if (staleData != null && !staleData.isEmpty()) {
                    log.warn("GitHub API unavailable - serving stale cached Terragrunt releases");
                    return new ResponseEntity<>(staleData, HttpStatus.OK);
                }
                log.error("GitHub API unavailable and no stale cache available - returning 503");
                return new ResponseEntity<>("{\"error\":\"Terragrunt releases temporarily unavailable\"}", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }
}
