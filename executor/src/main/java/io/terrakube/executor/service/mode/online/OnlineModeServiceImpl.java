package io.terrakube.executor.service.mode.online;

import io.terrakube.executor.service.mode.TerraformJob;
import io.terrakube.executor.service.executor.ExecutorJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/terraform-rs")
@Slf4j
public class OnlineModeServiceImpl {

    @Autowired
    ExecutorJob executorJob;

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)

    public ResponseEntity<TerraformJob> terraformJob(@RequestBody TerraformJob terraformJob) {
        log.debug("Received terraform job {}", terraformJob);
        executorJob.createJob(terraformJob);
        return new ResponseEntity<TerraformJob>(terraformJob, HttpStatus.ACCEPTED);
    }
}
