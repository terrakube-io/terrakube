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
@RequestMapping("/tofu")
public class TofuJsonController {

    private static final String TOFU_REDIS_KEY = "tofuReleasesResponse";
    private static final String TOFU_STALE_REDIS_KEY = "tofuReleasesResponseStale";
    TofuJsonProperties tofuJsonProperties;
    RedisTemplate redisTemplate;
    DownloadReleasesService downloadReleasesService;

    @GetMapping(value = "/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTofuReleases() throws IOException {
        String tofuIndex = "";
        if (redisTemplate.hasKey(TOFU_REDIS_KEY)) {
            log.info("Getting tofu releases from redis....");
            String tofuRedis = (String) redisTemplate.opsForValue().get(TOFU_REDIS_KEY);
            return new ResponseEntity<>(tofuRedis, HttpStatus.OK);
        } else {
            log.info("Getting tofu releases from default endpoint....");
            if (tofuJsonProperties.getReleasesUrl() != null && !tofuJsonProperties.getReleasesUrl().isEmpty()) {
                log.info("Using tofu releases URL {}", tofuJsonProperties.getReleasesUrl());
                tofuIndex = tofuJsonProperties.getReleasesUrl();
            } else {
                tofuIndex = "https://api.github.com/repos/opentofu/opentofu/releases";
                log.warn("Using tofu releases URL {}", tofuIndex);
            }

            try {
                Path pathTmp = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
                String tmpdir = Files.createDirectories(pathTmp).toFile().getAbsolutePath() + "/tofu-releases.json";
                log.info("Downloading tofu releases to {}", tmpdir);
                File tofuReleasesFile = new File(tmpdir);

                String githubToken = tofuJsonProperties.getGithubToken();
                if (githubToken != null && !githubToken.isEmpty()) {
                    log.info("Using authenticated GitHub API request");
                    downloadReleasesService.downloadReleasesToFile(tofuIndex, tofuReleasesFile, githubToken);
                } else {
                    log.warn("No GitHub token configured - using unauthenticated request (subject to rate limits)");
                    downloadReleasesService.downloadReleasesToFile(tofuIndex, tofuReleasesFile);
                }

                log.info("Downloaded tofu releases completed");
                tofuIndex = FileUtils.readFileToString(tofuReleasesFile, "UTF-8");
                log.info("Reading tofu releases completed");
                Files.deleteIfExists(tofuReleasesFile.toPath());
                log.info("Deleting temporary tofu files completed");

                log.warn("Saving tofu releases to redis...");
                redisTemplate.opsForValue().set(TOFU_REDIS_KEY, tofuIndex);
                redisTemplate.expire(TOFU_REDIS_KEY, tofuJsonProperties.getCacheExpirationMinutes(), TimeUnit.MINUTES);
                redisTemplate.opsForValue().set(TOFU_STALE_REDIS_KEY, tofuIndex);

                updateFallbackFileIfConfigured(tofuJsonProperties.getFallbackPath(), tofuIndex);

                return new ResponseEntity<>(tofuIndex, HttpStatus.OK);
            } catch (Exception e) {
                log.error("Failed to fetch OpenTofu releases from GitHub API: {}", e.getMessage());
                String staleData = (String) redisTemplate.opsForValue().get(TOFU_STALE_REDIS_KEY);
                if (staleData != null && !staleData.isEmpty()) {
                    log.warn("GitHub API unavailable - serving stale cached OpenTofu releases");
                    return new ResponseEntity<>(staleData, HttpStatus.OK);
                }

                String fallbackData = readFallbackFileIfAvailable(tofuJsonProperties.getFallbackPath());
                if (fallbackData != null && !fallbackData.isEmpty()) {
                    log.warn("GitHub API unavailable and no Redis cache - serving local fallback OpenTofu releases from {}",
                            tofuJsonProperties.getFallbackPath());
                    redisTemplate.opsForValue().set(TOFU_REDIS_KEY, fallbackData);
                    long expiration = tofuJsonProperties.getCacheExpirationMinutes() != null ? tofuJsonProperties.getCacheExpirationMinutes() : 30L;
                    redisTemplate.expire(TOFU_REDIS_KEY, expiration, TimeUnit.MINUTES);
                    redisTemplate.opsForValue().set(TOFU_STALE_REDIS_KEY, fallbackData);
                    return new ResponseEntity<>(fallbackData, HttpStatus.OK);
                }

                log.error("GitHub API unavailable and no stale cache or local fallback file available - returning 503");
                return new ResponseEntity<>("{\"error\":\"OpenTofu releases temporarily unavailable\"}", HttpStatus.SERVICE_UNAVAILABLE);
            }
        }
    }

    private File resolveFallbackFile(String fallbackPath) {
        if (fallbackPath == null || fallbackPath.isBlank()) {
            return null;
        }
        File file = new File(fallbackPath);
        if (file.exists()) {
            return file;
        }
        if (fallbackPath.startsWith("/workspaces/terrakube")) {
            File alt = new File(fallbackPath.replace("/workspaces/terrakube", System.getProperty("user.dir")));
            if (alt.exists()) {
                return alt;
            }
        }
        return file;
    }

    private String readFallbackFileIfAvailable(String fallbackPath) {
        File fallbackFile = resolveFallbackFile(fallbackPath);
        if (fallbackFile == null) {
            return null;
        }
        try {
            if (fallbackFile.exists() && fallbackFile.isFile() && fallbackFile.length() > 0) {
                log.info("Reading local fallback OpenTofu releases from {}", fallbackFile.getAbsolutePath());
                return FileUtils.readFileToString(fallbackFile, java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to read local fallback OpenTofu releases file from {}: {}", fallbackFile.getAbsolutePath(), e.getMessage());
        }
        return null;
    }

    private void updateFallbackFileIfConfigured(String fallbackPath, String content) {
        File fallbackFile = resolveFallbackFile(fallbackPath);
        if (fallbackFile == null) {
            return;
        }
        try {
            File parentDir = fallbackFile.getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                FileUtils.forceMkdir(parentDir);
            }
            FileUtils.writeStringToFile(fallbackFile, content, java.nio.charset.StandardCharsets.UTF_8);
            log.info("Updated local fallback OpenTofu releases file at {}", fallbackFile.getAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to update local fallback OpenTofu releases file at {}: {}", fallbackFile.getAbsolutePath(), e.getMessage());
        }
    }
}