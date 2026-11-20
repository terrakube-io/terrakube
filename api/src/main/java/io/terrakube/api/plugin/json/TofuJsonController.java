package io.terrakube.api.plugin.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@Slf4j
@AllArgsConstructor
@RestController
@RequestMapping("/tofu")
public class TofuJsonController {

    private static final String TOFU_REDIS_KEY = "tofuReleasesResponse";
    private static final String TOFU_STALE_REDIS_KEY = "tofuReleasesResponseStale";
    // A partial list (a later page failed) is cached briefly, so the next request soon tries the full list again.
    private static final long PARTIAL_CACHE_EXPIRATION_MINUTES = 1;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    TofuJsonProperties tofuJsonProperties;
    RedisTemplate redisTemplate;
    DownloadReleasesService downloadReleasesService;

    /**
     * Serves the whole OpenTofu releases list as a single JSON array without a Link header. GitHub pages the
     * list (30 releases by default), so every page is read here and cached together; the executor, which reads
     * this endpoint through terraform-spring-boot, then sees all releases in one response.
     */
    @GetMapping(value = "/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> getTofuReleases() {
        if (redisTemplate.hasKey(TOFU_REDIS_KEY)) {
            log.info("Getting tofu releases from redis....");
            String tofuRedis = (String) redisTemplate.opsForValue().get(TOFU_REDIS_KEY);
            return new ResponseEntity<>(tofuRedis, HttpStatus.OK);
        }

        log.info("Getting tofu releases from default endpoint....");
        String tofuIndex;
        if (tofuJsonProperties.getReleasesUrl() != null && !tofuJsonProperties.getReleasesUrl().isEmpty()) {
            log.info("Using tofu releases URL {}", tofuJsonProperties.getReleasesUrl());
            tofuIndex = tofuJsonProperties.getReleasesUrl();
        } else {
            tofuIndex = "https://api.github.com/repos/opentofu/opentofu/releases";
            log.warn("Using tofu releases URL {}", tofuIndex);
        }

        try {
            String githubToken = tofuJsonProperties.getGithubToken();
            if (githubToken == null || githubToken.isEmpty()) {
                log.warn("No GitHub token configured - using unauthenticated request (subject to rate limits)");
            }
            DownloadReleasesService.ReleasesPages pages = downloadReleasesService.downloadAllReleases(tofuIndex, githubToken);
            log.info("Downloaded {} tofu releases (complete: {})", pages.releases().size(), pages.complete());

            String releases;
            long expirationMinutes;
            if (pages.complete()) {
                releases = OBJECT_MAPPER.writeValueAsString(pages.releases());
                expirationMinutes = tofuJsonProperties.getCacheExpirationMinutes();
            } else {
                releases = OBJECT_MAPPER.writeValueAsString(
                        withStaleReleases(pages.releases(), (String) redisTemplate.opsForValue().get(TOFU_STALE_REDIS_KEY)));
                expirationMinutes = Math.min(PARTIAL_CACHE_EXPIRATION_MINUTES, tofuJsonProperties.getCacheExpirationMinutes());
            }

            log.warn("Saving tofu releases to redis...");
            redisTemplate.opsForValue().set(TOFU_REDIS_KEY, releases);
            redisTemplate.expire(TOFU_REDIS_KEY, expirationMinutes, TimeUnit.MINUTES);
            redisTemplate.opsForValue().set(TOFU_STALE_REDIS_KEY, releases);
            return new ResponseEntity<>(releases, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to fetch OpenTofu releases from GitHub API: {}", e.getMessage());
            String staleData = (String) redisTemplate.opsForValue().get(TOFU_STALE_REDIS_KEY);
            if (staleData != null && !staleData.isEmpty()) {
                log.warn("GitHub API unavailable - serving stale cached OpenTofu releases");
                return new ResponseEntity<>(staleData, HttpStatus.OK);
            }
            log.error("GitHub API unavailable and no stale cache available - returning 503");
            return new ResponseEntity<>("{\"error\":\"OpenTofu releases temporarily unavailable\"}", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * Completes a partial list with the releases of the last good list that it is missing, so a rate limit on
     * a later page does not hide older versions. A fetched release wins over its stale copy.
     */
    static ArrayNode withStaleReleases(ArrayNode fetched, String staleReleases) {
        if (staleReleases == null || staleReleases.isEmpty()) {
            return fetched;
        }
        JsonNode stale;
        try {
            stale = OBJECT_MAPPER.readTree(staleReleases);
        } catch (Exception e) {
            log.warn("Ignoring unreadable stale tofu releases: {}", e.getMessage());
            return fetched;
        }
        if (stale == null || !stale.isArray()) {
            return fetched;
        }
        ArrayNode merged = fetched.deepCopy();
        Set<String> fetchedVersions = new HashSet<>();
        fetched.forEach(release -> fetchedVersions.add(releaseVersion(release)));
        stale.forEach(release -> {
            String version = releaseVersion(release);
            // A release without a version cannot be matched, and the executor cannot resolve it anyway.
            if (version != null && !fetchedVersions.contains(version)) {
                merged.add(release);
            }
        });
        return merged;
    }

    // The release tag (falling back to its name) without a "v" prefix, as terraform-spring-boot matches it.
    private static String releaseVersion(JsonNode release) {
        String version = release.path("tag_name").asText("");
        if (version.isBlank()) {
            version = release.path("name").asText("");
        }
        version = version.trim();
        if (version.isEmpty()) {
            return null;
        }
        return version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
    }
}
