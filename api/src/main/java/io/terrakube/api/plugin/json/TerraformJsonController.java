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
@RequestMapping("/terraform")
public class TerraformJsonController {

    private static final String TERRAFORM_REDIS_KEY = "terraformReleasesResponse";
    private TerraformJsonProperties terraformJsonProperties;
    private DownloadReleasesService downloadReleasesService;
    RedisTemplate redisTemplate;

    @GetMapping(value = "/index.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> createToken() throws IOException {

        String terraformIndex = "";
        if (terraformJsonProperties.getReleasesUrl() != null && !terraformJsonProperties.getReleasesUrl().isEmpty()) {
            log.info("Using terraform releases URL {}", terraformJsonProperties.getReleasesUrl());
            terraformIndex = terraformJsonProperties.getReleasesUrl();
        } else {
            log.warn("Using terraform releases URL https://releases.hashicorp.com/terraform/index.json");
            terraformIndex = "https://releases.hashicorp.com/terraform/index.json";
        }

        if(redisTemplate.hasKey(TERRAFORM_REDIS_KEY)) {
            log.info("Getting terraform releases from redis....");
            String tofuRedis = (String) redisTemplate.opsForValue().get(TERRAFORM_REDIS_KEY);
            return new ResponseEntity<>(tofuRedis, HttpStatus.OK);
        } else {

            try {
                Path path = Paths.get(FileUtils.getTempDirectory().getAbsolutePath(), UUID.randomUUID().toString());
                String tmpdir = Files.createDirectories(path).toFile().getAbsolutePath() + "/terraform-releases.json";
                log.info("Downloading terraform releases to {}", tmpdir);
                File terraformReleasesFile = new File(tmpdir);
                downloadReleasesService.downloadReleasesToFile(terraformIndex, terraformReleasesFile);
                log.info("Downloaded terraform releases completed");
                terraformIndex = FileUtils.readFileToString(terraformReleasesFile, "UTF-8");
                log.info("Reading terraform releases completed");
                Files.deleteIfExists(terraformReleasesFile.toPath());
                log.info("Deleting temporary files completed");

                log.warn("Saving tofu releases to redis...");
                redisTemplate.opsForValue().set(TERRAFORM_REDIS_KEY, terraformIndex);
                redisTemplate.expire(TERRAFORM_REDIS_KEY, terraformJsonProperties.getCacheExpirationMinutes(), TimeUnit.MINUTES);

                return new ResponseEntity<>(terraformIndex, HttpStatus.OK);
            } catch (Exception e) {
                log.error(e.getMessage());
                return new ResponseEntity<>(terraformIndex, HttpStatus.INTERNAL_SERVER_ERROR);
            }
        }


    }
}


