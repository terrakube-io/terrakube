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
                downloadReleasesService.downloadReleasesToFile(tofuIndex, tofuReleasesFile);
                log.info("Downloaded tofu releases completed");
                tofuIndex = FileUtils.readFileToString(tofuReleasesFile, "UTF-8");
                log.info("Reading tofu releases completed");
                Files.deleteIfExists(tofuReleasesFile.toPath());
                log.info("Deleting temporary tofu files completed");

                log.warn("Saving tofu releases to redis...");
                redisTemplate.opsForValue().set(TOFU_REDIS_KEY, tofuIndex);
                redisTemplate.expire(TOFU_REDIS_KEY, 30, TimeUnit.MINUTES);
                return new ResponseEntity<>(tofuIndex, HttpStatus.OK);
            } catch (Exception e) {
                log.error(e.getMessage());
                return new ResponseEntity<>("", HttpStatus.INTERNAL_SERVER_ERROR);
            }

        }

    }
}


